package com.vishworks.assetmgmt.api;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The "apiserver" role: REST/webhook endpoints and the live dashboard. Component scanning
 * is limited to this role's packages, so no job workers are registered here.
 */
@SpringBootApplication(scanBasePackages = {
    "com.vishworks.assetmgmt.api",
    "com.vishworks.assetmgmt.config"})
public class ApiApplication {}
