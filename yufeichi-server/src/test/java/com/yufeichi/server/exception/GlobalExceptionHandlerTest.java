package com.yufeichi.server.exception;

import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.common.error.ErrorCode;
import com.yufeichi.server.common.result.Result;
import com.yufeichi.server.dto.LoginDTO;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ErrorEndpoints())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @CsvSource({"parameter,400,40000", "unauthorized,401,40100", "forbidden,403,40300",
            "not-found,404,40400", "duplicate,409,40900", "large,413,41300",
            "rate,429,42900", "server,500,50000", "auth-infrastructure,500,50000",
            "bad-password,401,50001", "disabled,401,50002", "unknown-business,500,99999"})
    void matchesHttpStatusAndBusinessCode(String kind, int http, int code) throws Exception {
        mvc.perform(get("/errors/" + kind))
                .andExpect(status().is(http))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(content().string(not(containsString("PRIVATE_SQL"))));
    }

    @Test
    void invalidJsonAndEmptyDtoAre400() throws Exception {
        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/validated").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void wrongParameterTypeIs400() throws Exception {
        mvc.perform(get("/typed").param("page", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mvc.perform(get("/validated"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value(40500));
    }

    @Test
    void successContractRemainsCodeZero() throws Exception {
        mvc.perform(get("/typed").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
    }

    // Test-only endpoints: no placeholder error or business routes are added to production.
    @RestController
    @org.springframework.context.annotation.Profile("handler-unit-test")
    static class ErrorEndpoints {
        @GetMapping("/errors/{kind}")
        void error(@PathVariable String kind) throws Exception {
            switch (kind) {
                case "parameter" -> throw new BusinessException(ErrorCode.PARAM_ERROR);
                case "unauthorized" -> throw new BadCredentialsException("PRIVATE_SQL");
                case "forbidden" -> throw new AccessDeniedException("PRIVATE_SQL");
                case "not-found" -> throw new NoResourceFoundException(HttpMethod.GET, "missing");
                case "duplicate" -> throw new DuplicateKeyException("PRIVATE_SQL");
                case "large" -> throw new MaxUploadSizeExceededException(100);
                case "rate" -> throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
                case "auth-infrastructure" -> throw new InternalAuthenticationServiceException("PRIVATE_SQL");
                case "bad-password" -> throw new BusinessException(ErrorCode.USERNAME_OR_PASSWORD_ERROR);
                case "disabled" -> throw new BusinessException(ErrorCode.USER_DISABLED);
                case "unknown-business" -> throw new BusinessException(99999, "unknown business error");
                default -> throw new IllegalStateException("PRIVATE_SQL");
            }
        }

        @PostMapping("/validated")
        Result<Void> validated(@Valid @RequestBody LoginDTO dto) {
            return Result.success();
        }

        @GetMapping("/typed")
        Result<Integer> typed(@RequestParam int page) {
            return Result.success(page);
        }
    }
}
