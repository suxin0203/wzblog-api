package com.wzblog.modules.media.infra.image;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.media.infra.config.FileStorageProperties;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * 图片校验与探测（M5 §7）：魔数 → 解码尺寸 → 像素上限。
 * <p>顺序固定且不整图解码：先用 ImageReader 只读尺寸，超限直接拒绝，防解压炸弹。
 * JPEG 读取 EXIF Orientation，width/height 记录方向修正后的可视尺寸。
 * WebP 无 JDK 原生解码器：仅做魔数校验，尺寸留空。</p>
 */
@Component
public class ImageAnalyzer {

    private final FileStorageProperties properties;

    public ImageAnalyzer(FileStorageProperties properties) {
        this.properties = properties;
    }

    /** 探测结果：mime 以魔数为准；orientation 为 EXIF 值（1~8，非 JPEG 恒为 1）。 */
    public record ImageMeta(String mimeType, int width, int height, int orientation) {
    }

    public ImageMeta analyze(byte[] bytes) {
        String mime = detectMagic(bytes);
        return switch (mime) {
            case "image/jpeg" -> analyzeJpeg(bytes);
            case "image/png" -> analyzePng(bytes);
            case "image/webp" -> new ImageMeta(mime, 0, 0, 1);
            default -> throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        };
    }

    /** 仅读取 EXIF Orientation（JPEG），不做完整校验；失败返回 1。 */
    public int orientationOf(byte[] bytes) {
        return readExifOrientation(bytes);
    }

    /** 声明扩展名与 MIME 的一致性预检（事实仍以魔数为准）。 */
    public void precheck(String filename, String contentType, long size) {
        if (size < 1 || size > properties.getMaxSizeBytes()) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        if (filename == null || filename.length() > 255 || filename.contains("/")
                || filename.contains("\\") || filename.contains("..")
                || filename.chars().anyMatch(c -> c < 0x20)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "文件名非法", null);
        }
        String ext = extensionOf(filename);
        String normalized = switch (ext) {
            case "jpg", "jpeg" -> "jpg";
            case "png", "webp" -> ext;
            default -> throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        };
        String declared = switch (contentType == null ? "" : contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        };
        if (!normalized.equals(declared)) {
            throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        }
    }

    public String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String detectMagic(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4E && bytes[3] == 0x47 && bytes[4] == 0x0D
                && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return "image/png";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        // SVG 与 GIF 明确拒绝
        throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
    }

    private ImageMeta analyzeJpeg(byte[] bytes) {
        int orientation = readExifOrientation(bytes);
        int[] size = decodeSize(bytes, "JPEG");
        checkPixels(size[0], size[1]);
        boolean swapped = orientation >= 5 && orientation <= 8;
        return new ImageMeta("image/jpeg", swapped ? size[1] : size[0], swapped ? size[0] : size[1],
                orientation);
    }

    private ImageMeta analyzePng(byte[] bytes) {
        int[] size = decodeSize(bytes, "png");
        checkPixels(size[0], size[1]);
        return new ImageMeta("image/png", size[0], size[1], 1);
    }

    /** 只读尺寸不整图解码；解码失败（损坏/伪装）按类型不支持处理。 */
    private int[] decodeSize(byte[] bytes, String format) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
                }
                return new int[]{width, height};
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED);
        }
    }

    private void checkPixels(int width, int height) {
        if (width > properties.getMaxSide() || height > properties.getMaxSide()
                || (long) width * height > properties.getMaxPixels()) {
            throw new BusinessException(ErrorCode.FILE_PIXEL_EXCEEDED);
        }
    }

    /**
     * 解析 JPEG APP1 中的 EXIF Orientation（tag 0x0112）。
     * 解析失败一律返回 1（无旋转），不影响主流程。
     */
    private int readExifOrientation(byte[] bytes) {
        try {
            int pos = 2; // 跳过 SOI
            while (pos + 4 <= bytes.length) {
                int marker = (bytes[pos] & 0xFF) << 8 | (bytes[pos + 1] & 0xFF);
                int length = (bytes[pos + 2] & 0xFF) << 8 | (bytes[pos + 3] & 0xFF);
                if (marker == 0xFFE1 && pos + 10 <= bytes.length && length >= 8
                        && bytes[pos + 4] == 'E' && bytes[pos + 5] == 'x'
                        && bytes[pos + 6] == 'i' && bytes[pos + 7] == 'f') {
                    return parseTiffOrientation(bytes, pos + 10, length - 8);
                }
                if ((marker & 0xFF00) != 0xFF00 || marker == 0xFFD8 || length <= 0) {
                    break;
                }
                pos += 2 + length;
            }
        } catch (Exception ignored) {
            // 解析失败视为无方向信息
        }
        return 1;
    }

    private int parseTiffOrientation(byte[] bytes, int tiffStart, int tiffLength) {
        if (tiffStart + 8 > bytes.length) {
            return 1;
        }
        boolean littleEndian = bytes[tiffStart] == 0x49 && bytes[tiffStart + 1] == 0x49;
        int ifdOffset = readInt(bytes, tiffStart + 4, littleEndian);
        int ifdStart = tiffStart + ifdOffset;
        if (ifdStart + 2 > bytes.length) {
            return 1;
        }
        int entryCount = readShort(bytes, ifdStart, littleEndian);
        for (int i = 0; i < entryCount && ifdStart + 2 + i * 12 + 12 <= bytes.length; i++) {
            int entry = ifdStart + 2 + i * 12;
            int tag = readShort(bytes, entry, littleEndian);
            if (tag == 0x0112) {
                int value = readShort(bytes, entry + 8, littleEndian);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private int readShort(byte[] bytes, int offset, boolean littleEndian) {
        int b0 = bytes[offset] & 0xFF;
        int b1 = bytes[offset + 1] & 0xFF;
        return littleEndian ? (b1 << 8) | b0 : (b0 << 8) | b1;
    }

    private int readInt(byte[] bytes, int offset, boolean littleEndian) {
        int s1 = readShort(bytes, offset, littleEndian);
        int s2 = readShort(bytes, offset + 2, littleEndian);
        return littleEndian ? (s2 << 16) | s1 : (s1 << 16) | s2;
    }
}
