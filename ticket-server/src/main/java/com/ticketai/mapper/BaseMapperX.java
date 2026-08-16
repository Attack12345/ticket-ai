package com.ticketai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 自定义 Mapper 基类（DEV_DOC §3.1）。
 * 后续可按需扩展便捷方法（如 insertBatch、likeIfPresent 等），各业务 Mapper 统一继承本接口。
 */
public interface BaseMapperX<T> extends BaseMapper<T> {
}
