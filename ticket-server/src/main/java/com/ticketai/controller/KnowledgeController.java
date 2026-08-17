package com.ticketai.controller;

import com.ticketai.common.PageResult;
import com.ticketai.common.Result;
import com.ticketai.dto.KbSearchDTO;
import com.ticketai.dto.KnowledgeBaseDTO;
import com.ticketai.service.KnowledgeBaseService;
import com.ticketai.vo.KbSearchHitVO;
import com.ticketai.vo.KnowledgeBaseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
@Tag(name = "知识库")
public class KnowledgeController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "知识库分页列表")
    public Result<PageResult<KnowledgeBaseVO>> list(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size,
                                                    @RequestParam(required = false) String keyword) {
        return Result.ok(knowledgeBaseService.pageList(page, size, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "文章详情")
    public Result<KnowledgeBaseVO> detail(@PathVariable Long id) {
        return Result.ok(knowledgeBaseService.getDetail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('kb:manage')")
    @Operation(summary = "创建文章（自动分段并建索引）")
    public Result<Map<String, Object>> create(@RequestBody @Valid KnowledgeBaseDTO dto) {
        return Result.ok(Map.of("id", knowledgeBaseService.create(dto)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('kb:manage')")
    @Operation(summary = "更新文章（重建分段与索引）")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid KnowledgeBaseDTO dto) {
        knowledgeBaseService.update(id, dto);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('kb:manage')")
    @Operation(summary = "删除文章（逻辑删除 + ES 删除）")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return Result.ok();
    }

    @PostMapping("/search")
    @PreAuthorize("hasAuthority('ticket:view')")
    @Operation(summary = "知识库检索（全文 + 可选语义向量，embedding 不可用自动降级）")
    public Result<List<KbSearchHitVO>> search(@RequestBody @Valid KbSearchDTO dto) {
        return Result.ok(knowledgeBaseService.search(dto));
    }
}
