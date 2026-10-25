package com.wzblog.modules.interaction.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * M3 互动模块配置（wzblog.interaction.*）：评论/留言限流阈值。
 */
@Component
@ConfigurationProperties(prefix = "wzblog.interaction")
public class InteractionProperties {

    /** 登录用户评论：短窗秒数与短窗上限。 */
    private int userCommentShortWindow = 10;
    private int userCommentShortLimit = 1;
    private int userCommentDailyLimit = 50;

    /** 游客评论：短窗秒数与短窗上限。 */
    private int guestCommentShortWindow = 60;
    private int guestCommentShortLimit = 1;
    private int guestCommentDailyLimit = 10;

    /** 留言（按 IP）。 */
    private int guestbookShortWindow = 60;
    private int guestbookShortLimit = 1;
    private int guestbookDailyLimit = 10;

    public int getUserCommentShortWindow() {
        return userCommentShortWindow;
    }

    public void setUserCommentShortWindow(int userCommentShortWindow) {
        this.userCommentShortWindow = userCommentShortWindow;
    }

    public int getUserCommentShortLimit() {
        return userCommentShortLimit;
    }

    public void setUserCommentShortLimit(int userCommentShortLimit) {
        this.userCommentShortLimit = userCommentShortLimit;
    }

    public int getUserCommentDailyLimit() {
        return userCommentDailyLimit;
    }

    public void setUserCommentDailyLimit(int userCommentDailyLimit) {
        this.userCommentDailyLimit = userCommentDailyLimit;
    }

    public int getGuestCommentShortWindow() {
        return guestCommentShortWindow;
    }

    public void setGuestCommentShortWindow(int guestCommentShortWindow) {
        this.guestCommentShortWindow = guestCommentShortWindow;
    }

    public int getGuestCommentShortLimit() {
        return guestCommentShortLimit;
    }

    public void setGuestCommentShortLimit(int guestCommentShortLimit) {
        this.guestCommentShortLimit = guestCommentShortLimit;
    }

    public int getGuestCommentDailyLimit() {
        return guestCommentDailyLimit;
    }

    public void setGuestCommentDailyLimit(int guestCommentDailyLimit) {
        this.guestCommentDailyLimit = guestCommentDailyLimit;
    }

    public int getGuestbookShortWindow() {
        return guestbookShortWindow;
    }

    public void setGuestbookShortWindow(int guestbookShortWindow) {
        this.guestbookShortWindow = guestbookShortWindow;
    }

    public int getGuestbookShortLimit() {
        return guestbookShortLimit;
    }

    public void setGuestbookShortLimit(int guestbookShortLimit) {
        this.guestbookShortLimit = guestbookShortLimit;
    }

    public int getGuestbookDailyLimit() {
        return guestbookDailyLimit;
    }

    public void setGuestbookDailyLimit(int guestbookDailyLimit) {
        this.guestbookDailyLimit = guestbookDailyLimit;
    }
}
