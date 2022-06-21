// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.myorg.buildAndPublishPackage;
import software.amazon.awscdk.services.codebuild.*;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.kms.IKey;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.Map;

public class buildAndPublishPackage extends Construct {

    public PipelineProject project;

    public buildAndPublishPackage(final Construct scope, final String id, final String projectName, final String codeartifactDomainArn, final String codeartifactRepoArn, final IKey artifactBucketEncryptionKey) {
        this(scope, id, null, projectName, codeartifactDomainArn, codeartifactRepoArn, artifactBucketEncryptionKey);
    }

    public buildAndPublishPackage(final Construct scope, final String id, final PipelineProjectProps props, final String projectName, final String codeartifactDomainArn, final String codeartifactRepoArn, final IKey artifactBucketEncryptionKey) {
        super(scope, id);

        project = PipelineProject.Builder.create(this, projectName)
                .environment(BuildEnvironment.builder()
                        .privileged(false)
                        .computeType(ComputeType.MEDIUM)
                        .buildImage(LinuxBuildImage.STANDARD_5_0)
                        .build()
                )
                .encryptionKey(artifactBucketEncryptionKey)
                .buildSpec(BuildSpec.fromObject(Map.of(
                        "version", "0.2",
                        "phases", Map.of(
                                "pre_build", Map.of(
                                        "commands", Arrays.asList(
                                                "export CODEARTIFACT_AUTH_TOKEN=`aws codeartifact get-authorization-token --domain aws-java-sample-domain --query authorizationToken --output text`",
                                                "export CODEARTIFACT_REPOSITORY_URL=`aws codeartifact get-repository-endpoint --domain aws-java-sample-domain --repository mvn --format maven --query repositoryEndpoint --output text`",
                                                "cd ./packages/" + projectName
                                        )
                                ),
                                "build", Map.of(
                                        "commands", Arrays.asList(
                                                "mvn deploy --settings settings.xml"
                                        )
                                )
                        )
                )))
                .build();

        project.getRole().attachInlinePolicy(
                Policy.Builder.create(this, "PublishPolicy")
                        .statements(Arrays.asList(
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(Arrays.asList("*"))
                                        .actions(Arrays.asList("sts:GetServiceBearerToken"))
                                        .conditions(Map.of(
                                                "StringEquals", Map.of(
                                                        "sts:AWSServiceName", "codeartifact.amazonaws.com"
                                                )
                                        ))
                                        .build(),
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(Arrays.asList(codeartifactDomainArn))
                                        .actions(Arrays.asList("codeartifact:GetAuthorizationToken"))
                                        .build(),
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(Arrays.asList(codeartifactRepoArn))
                                        .actions(Arrays.asList(
                                                "codeartifact:ReadFromRepository",
                                                "codeartifact:GetRepositoryEndpoint",
                                                "codeartifact:List*"
                                        ))
                                        .build(),
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(Arrays.asList("*"))
                                        .actions(Arrays.asList(
                                                "codeartifact:PublishPackageVersion",
                                                "codeartifact:PutPackageMetadata"
                                        ))
                                        .build()
                        ))
                        .build()
        );
    }
}
