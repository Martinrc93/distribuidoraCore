package com.distribuidora.shared.web;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {
    @Test
    void calculatesTotalPagesFromElementsAndPageSize() {
        PageResponse<String> response = PageResponse.of(List.of("a"), 2, 20, 41);

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(41);
        assertThat(response.totalPages()).isEqualTo(3);
    }
}
