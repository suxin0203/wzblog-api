package com.wzblog.modules.media.infra.image;

import com.wzblog.modules.media.domain.event.FileActivatedEvent;
import com.wzblog.modules.media.infra.config.FileStorageProperties;
import com.wzblog.modules.media.infra.mapper.FileMapper;
import com.wzblog.modules.media.infra.storage.ObjectStorage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 缩略图生成：事务提交后异步执行，最长边 320、等比、JPEG q=0.82、重绘剥离 EXIF。
 * <p>失败仅告警不重试：thumb_key 保持 NULL，前端回退原图；读取永远以 thumb_key 列为准。</p>
 */
@Component
public class ThumbnailGenerator {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailGenerator.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM", Locale.ROOT);

    private final FileMapper fileMapper;
    private final ObjectStorage objectStorage;
    private final ImageAnalyzer imageAnalyzer;
    private final FileStorageProperties properties;
    private final ThreadPoolExecutor executor;

    public ThumbnailGenerator(FileMapper fileMapper, ObjectStorage objectStorage,
                              ImageAnalyzer imageAnalyzer, FileStorageProperties properties) {
        this.fileMapper = fileMapper;
        this.objectStorage = objectStorage;
        this.imageAnalyzer = imageAnalyzer;
        this.properties = properties;
        this.executor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.DiscardOldestPolicy());
        this.executor.setThreadFactory(runnable -> {
            Thread thread = new Thread(runnable, "media-thumbnail");
            thread.setDaemon(true);
            return thread;
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFileActivated(FileActivatedEvent event) {
        byte[] bytes = objectStorage.get(event.objectKey());
        if (bytes == null) {
            log.warn("缩略图跳过：对象不存在 fileId={}, key={}", event.fileId(), event.objectKey());
            return;
        }
        executor.execute(() -> {
            try {
                generate(event.fileId(), event.objectKey(), bytes);
            } catch (Exception ex) {
                log.warn("缩略图生成失败 fileId={}, key={}", event.fileId(), event.objectKey(), ex);
            }
        });
    }

    void generate(Long fileId, String objectKey, byte[] bytes) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            log.warn("缩略图解码失败 fileId={}, key={}", fileId, objectKey, ex);
            return;
        }
        if (source == null) {
            return; // WebP 等无法解码的格式不生成缩略图
        }
        BufferedImage oriented = applyOrientation(source, imageAnalyzer.orientationOf(bytes));

        double longest = Math.max(oriented.getWidth(), oriented.getHeight());
        int limit = properties.getThumbLongest();
        if (longest <= limit) {
            return; // 已足够小，不生成
        }
        double scale = limit / longest;
        int targetWidth = Math.max(1, (int) Math.round(oriented.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(oriented.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.drawImage(oriented, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }

        byte[] encoded = encodeJpeg(thumbnail, properties.getThumbQuality());
        if (encoded == null) {
            log.warn("缩略图编码失败 fileId={}", fileId);
            return;
        }
        String thumbKey = thumbKeyOf(objectKey);
        objectStorage.put(thumbKey, encoded);
        fileMapper.updateThumbKey(fileId, thumbKey, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        log.info("缩略图已生成 fileId={}, thumbKey={}", fileId, thumbKey);
    }

    /** 按 EXIF Orientation 旋转/镜像；值非法时原样返回。经典变换矩阵，源尺寸为基准。 */
    static BufferedImage applyOrientation(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform transform = new AffineTransform();
        switch (orientation) {
            case 2 -> transform.scale(-1.0, 1.0);
            case 3 -> {
                transform.translate(width, height);
                transform.rotate(Math.PI);
            }
            case 4 -> transform.scale(1.0, -1.0);
            case 5 -> {
                transform.rotate(-Math.PI / 2);
                transform.scale(-1.0, 1.0);
            }
            case 6 -> {
                transform.translate(height, 0);
                transform.rotate(Math.PI / 2);
            }
            case 7 -> {
                transform.scale(-1.0, 1.0);
                transform.translate(-height, 0);
                transform.translate(0, width);
                transform.rotate(3 * Math.PI / 2);
            }
            case 8 -> {
                transform.translate(0, width);
                transform.rotate(3 * Math.PI / 2);
            }
            default -> {
                return source;
            }
        }
        BufferedImage target = new BufferedImage(swap ? height : width, swap ? width : height,
                BufferedImage.TYPE_INT_RGB);
        new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR).filter(source, target);
        return target;
    }

    private byte[] encodeJpeg(BufferedImage image, double quality) {
        try {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) {
                return null;
            }
            ImageWriter writer = writers.next();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(stream);
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality((float) quality);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return output.toByteArray();
        } catch (Exception ex) {
            log.warn("JPEG 编码异常", ex);
            return null;
        }
    }

    private String thumbKeyOf(String objectKey) {
        String biz = objectKey.substring(0, objectKey.indexOf('/'));
        String month = MONTH.format(LocalDateTime.now(ZoneOffset.UTC));
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return biz + "/" + month + "/" + uuid + "_thumb.jpg";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
