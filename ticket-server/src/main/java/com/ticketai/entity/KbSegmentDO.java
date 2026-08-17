package com.ticketai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库分段（kb_segment）。向量存 ES（id 对应），此处存元数据。
 */
@Data
@TableName("kb_segment")
public class KbSegmentDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kbId;

    /** 段序号 */
    private Integer seq;

    private String content;

    private Integer charCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
