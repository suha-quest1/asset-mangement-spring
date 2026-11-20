package com.vishworks.assetmgmt.worker;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The "worker" role: deploys the BPMN/form resources and runs the job workers of every
 * service task. Component scanning is limited to the packages of this role, so the API
 * server role never registers job workers.
 */
@SpringBootApplication(scanBasePackages = {
    "com.vishworks.assetmgmt.worker",
    "com.vishworks.assetmgmt.service",
    "com.vishworks.assetmgmt.handlers",
    "com.vishworks.assetmgmt.camunda",
    "com.vishworks.assetmgmt.config"})
public class WorkerApplication {}
