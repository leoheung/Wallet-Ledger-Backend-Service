package com.wallet.ledger.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Stable pagination envelope independent of Spring Data's JSON shape. */
public record PagedResponse<T>(List<T> content,
                               int page,
                               int size,
                               long totalElements,
                               int totalPages) {

    public static <S, T> PagedResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PagedResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
