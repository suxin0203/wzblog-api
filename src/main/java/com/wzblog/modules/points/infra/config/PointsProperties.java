package com.wzblog.modules.points.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M4 积分规则配置（wzblog.points.*，00 §7 默认值）。
 */
@Component
@ConfigurationProperties(prefix = "wzblog.points")
public class PointsProperties {

    private int dailyLoginAmount = 5;
    private int articlePublishAmount = 10;
    private int articlePublishDailyCap = 100;
    private int commentApprovedAmount = 5;
    private int commentApprovedDailyCap = 50;
    private int likeReceivedAmount = 1;

    public int getDailyLoginAmount() {
        return dailyLoginAmount;
    }

    public void setDailyLoginAmount(int dailyLoginAmount) {
        this.dailyLoginAmount = dailyLoginAmount;
    }

    public int getArticlePublishAmount() {
        return articlePublishAmount;
    }

    public void setArticlePublishAmount(int articlePublishAmount) {
        this.articlePublishAmount = articlePublishAmount;
    }

    public int getArticlePublishDailyCap() {
        return articlePublishDailyCap;
    }

    public void setArticlePublishDailyCap(int articlePublishDailyCap) {
        this.articlePublishDailyCap = articlePublishDailyCap;
    }

    public int getCommentApprovedAmount() {
        return commentApprovedAmount;
    }

    public void setCommentApprovedAmount(int commentApprovedAmount) {
        this.commentApprovedAmount = commentApprovedAmount;
    }

    public int getCommentApprovedDailyCap() {
        return commentApprovedDailyCap;
    }

    public void setCommentApprovedDailyCap(int commentApprovedDailyCap) {
        this.commentApprovedDailyCap = commentApprovedDailyCap;
    }

    public int getLikeReceivedAmount() {
        return likeReceivedAmount;
    }

    public void setLikeReceivedAmount(int likeReceivedAmount) {
        this.likeReceivedAmount = likeReceivedAmount;
    }
}
