package com.ticketai.service;

import com.ticketai.dto.SlaPolicyDTO;
import com.ticketai.vo.SlaPolicyVO;

import java.util.List;

public interface SlaPolicyService {

    List<SlaPolicyVO> list();

    Long create(SlaPolicyDTO dto);

    void update(Long id, SlaPolicyDTO dto);

    void delete(Long id);
}
