package com.distribuidora.expense.api;

import com.distribuidora.expense.application.ExpenseService;
import com.distribuidora.expense.application.ExpenseService.Entry;
import com.distribuidora.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/expenses")
@PreAuthorize("hasAuthority('ADMIN_ALL')")
public class ExpenseController {
    private final ExpenseService expenses;

    public ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @GetMapping
    public PageResponse<Entry> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return expenses.list(page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Entry create(@Valid @RequestBody CreateExpense input) {
        return expenses.create(input.idempotencyKey(), input.amount(), input.type(), input.observation());
    }

    public record CreateExpense(
        @NotNull UUID idempotencyKey,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "PETTY_CASH|OTHER|SERVICES|FUEL") String type,
        @Size(max = 500) String observation
    ) { }
}
