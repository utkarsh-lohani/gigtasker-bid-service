package com.gigtasker.bidservice.dto;

import com.gigtasker.bidservice.enums.TaskStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TaskDTO {
    private Long id;
    private String title;
    private String description;
    private Long posterUserId;
    private TaskStatus status;
    private LocalDateTime deadline;
    private BigDecimal minPay;
    private BigDecimal maxPay;
    private Integer maxBidsPerUser;
}
