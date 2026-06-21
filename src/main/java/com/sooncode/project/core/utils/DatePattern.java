package com.sooncode.project.core.utils;

public interface DatePattern {
    public static final String[] PARSE_PATTERNS = new String[]{
            DatePattern.DATE,
            DatePattern.DATETIME,
            DatePattern.DATETIME_MM,
            DatePattern.DATETIME_SSS,
            DatePattern.SYS_DATE,
            DatePattern.SYS_DATETIME,
            DatePattern.SYS_DATETIME_MM,
            DatePattern.SYS_DATETIME_SSS
    };
    //
    // 常规模式
    // ----------------------------------------------------------------------------------------------------
    /**
     * yyyy-MM-dd
     */
    String DATE = "yyyy-MM-dd";
    /**
     * yyyy-MM-dd HH:mm:ss
     */
    String DATETIME = "yyyy-MM-dd HH:mm:ss";
    /**
     * yyyy-MM-dd HH:mm
     */
    String DATETIME_MM = "yyyy-MM-dd HH:mm";
    /**
     * yyyy-MM-dd HH:mm:ss.SSS
     */
    String DATETIME_SSS = "yyyy-MM-dd HH:mm:ss.SSS";
    /**
     * HH:mm
     */
    String TIME = "HH:mm";
    /**
     * HH:mm:ss
     */
    String TIME_SS = "HH:mm:ss";

    //
    // 系统时间格式
    // ----------------------------------------------------------------------------------------------------
    /**
     * yyyy/MM/dd
     */
    String SYS_DATE = "yyyy/MM/dd";
    /**
     * yyyy/MM/dd HH:mm:ss
     */
    String SYS_DATETIME = "yyyy/MM/dd HH:mm:ss";
    /**
     * yyyy/MM/dd HH:mm
     */
    String SYS_DATETIME_MM = "yyyy/MM/dd HH:mm";
    /**
     * yyyy/MM/dd HH:mm:ss.SSS
     */
    String SYS_DATETIME_SSS = "yyyy/MM/dd HH:mm:ss.SSS";

    //
    // 无连接符模式
    // ----------------------------------------------------------------------------------------------------
    /**
     * yyyyMMdd
     */
    String NONE_DATE = "yyyyMMdd";
    /**
     * yyyyMMddHHmmss
     */
    String NONE_DATETIME = "yyyyMMddHHmmss";
    /**
     * yyyyMMddHHmm
     */
    String NONE_DATETIME_MM = "yyyyMMddHHmm";
    /**
     * yyyyMMddHHmmssSSS
     */
    String NONE_DATETIME_SSS = "yyyyMMddHHmmssSSS";
}
