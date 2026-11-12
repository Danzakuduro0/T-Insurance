package ru.tinsurance.server.files.storage

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

internal object S3ClientFactory {
    fun create(config: S3Config): S3Client = S3Client.builder()
        .endpointOverride(config.endpoint)
        .region(Region.of(config.region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)))
        .forcePathStyle(config.pathStyle)
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .httpClientBuilder(UrlConnectionHttpClient.builder()
            .connectionTimeout(config.connectTimeout)
            .socketTimeout(config.timeout)
            .apply {
                if (config.endpoint.isLoopback()) {
                    proxyConfiguration { it.useSystemPropertyValues(false).useEnvironmentVariablesValues(false) }
                }
            })
        .overrideConfiguration { it.apiCallTimeout(config.timeout).apiCallAttemptTimeout(config.timeout) }
        .build()
}
