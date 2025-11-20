package com.gigtasker.bidservice.config;

import org.gigtasker.gigtaskercommon.security.GigTaskerSecurity;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(GigTaskerSecurity.class)
public class SecurityConfig {
}
