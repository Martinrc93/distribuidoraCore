package com.distribuidora.settings.application;

public record BusinessProfile(String name, String address, String phone) {
    public static final BusinessProfile EMPTY = new BusinessProfile("", "", "");
}
