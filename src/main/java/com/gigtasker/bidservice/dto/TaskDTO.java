package com.gigtasker.bidservice.dto;

import com.gigtasker.bidservice.enums.TaskStatus;
import lombok.Data;

@Data
public class TaskDTO {
    private Long id;
    private String title;
    private String description;
    private Long posterUserId;
    private TaskStatus status;
}
