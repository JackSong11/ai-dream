package com.example.dream.service.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneRulesException;

@Component
public class AgentBuiltinTools {

    @Tool(description = "获取指定 IANA 时区的当前日期和时间")
    public String currentDateTime(@ToolParam(description = "IANA 时区，例如 Asia/Shanghai") String timezone) {
        try {
            return ZonedDateTime.now(java.time.ZoneId.of(timezone)).format(DateTimeFormatter.ISO_ZONED_DATE_TIME);
        } catch (ZoneRulesException e) {
            return "Error: unknown timezone " + timezone;
        }
    }
}
