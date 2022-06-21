// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.myorg;

import com.myorg.buildAndPublishPackage.buildAndPublishPackage;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.codebuild.*;
import software.amazon.awscdk.services.codepipeline.StageOptions;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildAction;
import software.amazon.awscdk.services.codepipeline.actions.CodeBuildActionProps;
import software.amazon.awscdk.services.codepipeline.actions.CodeCommitSourceAction;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.Policy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.constructs.Construct;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.codecommit.Repository;
import software.amazon.awscdk.services.codeartifact.CfnDomain;
import software.amazon.awscdk.services.codeartifact.CfnRepository;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.codepipeline.Pipeline;
import software.amazon.awscdk.services.codepipeline.Artifact;

import io.github.cdklabs.cdknag.NagSuppressions;
import io.github.cdklabs.cdknag.NagPackSuppression;

import java.util.Arrays;
import java.util.Map;

public class JavaCdkCicdCodeartifactStack extends Stack {
    public JavaCdkCicdCodeartifactStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public JavaCdkCicdCodeartifactStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        final Repository repo = Repository.Builder.create(this, "CodeCommitRepository")
                .repositoryName("JavaSampleRepository")
                .build();

        final CfnDomain codeartifactDomain = CfnDomain.Builder.create(this, "CodeArtifactDomain")
                .domainName("aws-java-sample-domain")
                .build();

        final CfnRepository mvnMirrorCodeartifactRepository = CfnRepository.Builder.create(this, "MvnMirrorCodeArtifactRepository")
                .domainName(codeartifactDomain.getDomainName())
                .repositoryName("mvn-mirror")
                .externalConnections(Arrays.asList("public:maven-central"))
                .build();

        final CfnRepository mvnPrivateCodeartifactRepository = CfnRepository.Builder.create(this, "MvnPrivateCodeArtifactRepository")
                .domainName(codeartifactDomain.getDomainName())
                .repositoryName("mvn")
                .upstreams(Arrays.asList(mvnMirrorCodeartifactRepository.getRepositoryName()))
                .build();

        mvnMirrorCodeartifactRepository.addDependsOn(codeartifactDomain);
        mvnPrivateCodeartifactRepository.addDependsOn(mvnMirrorCodeartifactRepository);

        final Bucket pipelineArtifactBucket = Bucket.Builder.create(this, "PipelineArtifactBucket")
                .bucketName("sample-java-cdk-artifact-" + this.getAccount())
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .encryption(BucketEncryption.S3_MANAGED)
                .removalPolicy(RemovalPolicy.DESTROY)
                .enforceSsl(true)
                .autoDeleteObjects(true)
                .build();

        NagSuppressions.addResourceSuppressions(pipelineArtifactBucket, Arrays.asList(
                new NagPackSuppression.Builder()
                        .id("AwsSolutions-S1")
                        .reason("Cannot log to itself")
                        .build()
        ), true);

        final Pipeline pipeline = Pipeline.Builder.create(this, "PackagePipeline")
                .pipelineName("java-sample-pipeline")
                .restartExecutionOnUpdate(true)
                .artifactBucket(pipelineArtifactBucket)
                .build();

        final Artifact sourceOutput = new Artifact("SourceArtifact");

        final CodeCommitSourceAction sourceAction = CodeCommitSourceAction.Builder.create()
                .actionName("CodeCommit")
                .repository(repo)
                .output(sourceOutput)
                .branch("main")
                .build();

        pipeline.addStage(StageOptions.builder()
                .stageName("Source")
                .actions(Arrays.asList(sourceAction))
                .build()
        );

        final PipelineProject runUnitTestsProject = PipelineProject.Builder.create(this, "RunUnitTests")
                .environment(BuildEnvironment.builder()
                        .privileged(false)
                        .computeType(ComputeType.MEDIUM)
                        .buildImage(LinuxBuildImage.STANDARD_5_0)
                        .build()
                )
                .encryptionKey(pipeline.getArtifactBucket().getEncryptionKey())
                .buildSpec(BuildSpec.fromObject(Map.of(
                        "version", "0.2",
                        "phases", Map.of(
                                "pre_build", Map.of(
                                        "commands", Arrays.asList(
                                                "export CODEARTIFACT_AUTH_TOKEN=`aws codeartifact get-authorization-token --domain aws-java-sample-domain --query authorizationToken --output text`",
                                                "export CODEARTIFACT_REPOSITORY_URL=`aws codeartifact get-repository-endpoint --domain aws-java-sample-domain --repository mvn --format maven --query repositoryEndpoint --output text`"
                                        )
                                ),
                                "build", Map.of(
                                        "commands", Arrays.asList(
                                                "mvn package --settings settings.xml"
                                        )
                                )
                        )
                )))
                .build();

        runUnitTestsProject.getRole().attachInlinePolicy(
                Policy.Builder.create(this, "RunUnitTestsPolicy")
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
                                        .resources(Arrays.asList(codeartifactDomain.getAttrArn()))
                                        .actions(Arrays.asList("codeartifact:GetAuthorizationToken"))
                                        .build(),
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(Arrays.asList(mvnPrivateCodeartifactRepository.getAttrArn()))
                                        .actions(Arrays.asList(
                                                "codeartifact:ReadFromRepository",
                                                "codeartifact:GetRepositoryEndpoint",
                                                "codeartifact:List*"
                                        ))
                                        .build()

                        ))
                        .build()
        );

        pipeline.addStage(StageOptions.builder()
                .stageName("Test")
                .actions(Arrays.asList(
                        new CodeBuildAction(CodeBuildActionProps.builder()
                                .actionName("run-unit-tests")
                                .project(runUnitTestsProject)
                                .input(sourceOutput)
                                .build())
                ))
                .build()
        );

        final PipelineProject selfMutateProject = PipelineProject.Builder.create(this, "SelfMutate")
                .environment(BuildEnvironment.builder()
                        .privileged(false)
                        .computeType(ComputeType.MEDIUM)
                        .buildImage(LinuxBuildImage.STANDARD_5_0)
                        .build()
                )
                .encryptionKey(pipeline.getArtifactBucket().getEncryptionKey())
                .buildSpec(BuildSpec.fromObject(Map.of(
                        "version", "0.2",
                        "phases", Map.of(
                                "pre_build", Map.of(
                                        "commands", Arrays.asList(
                                                "npm install -g aws-cdk",
                                                "export CODEARTIFACT_AUTH_TOKEN=`aws codeartifact get-authorization-token --domain aws-java-sample-domain --query authorizationToken --output text`",
                                                "export CODEARTIFACT_REPOSITORY_URL=`aws codeartifact get-repository-endpoint --domain aws-java-sample-domain --repository mvn --format maven --query repositoryEndpoint --output text`",
                                                "export CODEARTIFACT_ACCOUNT_ID=`aws sts get-caller-identity --query \"Account\" --output text`"
                                        )
                                ),
                                "build", Map.of(
                                        "commands", Arrays.asList(
                                                "cdk deploy --require-approval=never"
                                        )
                                )
                        )
                )))
                .build();

        selfMutateProject.getRole().attachInlinePolicy(
                Policy.Builder.create(this, "SelfMutatePolicy")
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
                                        .resources(Arrays.asList(codeartifactDomain.getAttrArn()))
                                        .actions(Arrays.asList("codeartifact:GetAuthorizationToken"))
                                        .build(),
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(Arrays.asList(mvnPrivateCodeartifactRepository.getAttrArn()))
                                        .actions(Arrays.asList(
                                                "codeartifact:ReadFromRepository",
                                                "codeartifact:GetRepositoryEndpoint",
                                                "codeartifact:List*"
                                        ))
                                        .build(),
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(Arrays.asList("*"))
                                        .actions(Arrays.asList("cloudformation:DescribeStacks"))
                                        .build(),
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(Arrays.asList("*"))
                                        .actions(Arrays.asList("iam:PassRole"))
                                        .build(),
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .resources(Arrays.asList("arn:aws:iam::*:role/cdk-*"))
                                        .actions(Arrays.asList("sts:AssumeRole"))
                                        .build()
                        ))
                        .build()
        );

        pipeline.addStage(StageOptions.builder()
                .stageName("UpdatePipeline")
                .actions(Arrays.asList(
                        new CodeBuildAction(CodeBuildActionProps.builder()
                                .actionName("self-mutate")
                                .project(selfMutateProject)
                                .input(sourceOutput)
                                .build())
                ))
                .build()
        );

        final buildAndPublishPackage samplePackageProject = new buildAndPublishPackage(this, "BuildSamplePackage", "sample-package", codeartifactDomain.getAttrArn(), mvnPrivateCodeartifactRepository.getAttrArn(), pipelineArtifactBucket.getEncryptionKey());

        pipeline.addStage(StageOptions.builder()
                .stageName("BuildAndPublishPackages")
                .actions(Arrays.asList(
                        new CodeBuildAction(CodeBuildActionProps.builder()
                                .actionName("sample-package")
                                .project(samplePackageProject.project)
                                .input(sourceOutput)
                                .build())
                ))
                .build()
        );

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/PackagePipeline/Role/DefaultPolicy/Resource",
                Arrays.asList(
                new NagPackSuppression.Builder()
                        .id("AwsSolutions-IAM5")
                        .reason("Defined by a default policy")
                        .appliesTo(Arrays.asList(
                                "Action::s3:Abort*",
                                "Action::s3:DeleteObject*",
                                "Action::s3:GetBucket*",
                                "Action::s3:GetObject*",
                                "Action::s3:List*",
                                "Resource::<PipelineArtifactBucketD127CCF6.Arn>/*"
                        ))
                        .build()
                ));

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/PackagePipeline/Source/CodeCommit/CodePipelineActionRole/DefaultPolicy/Resource",
                Arrays.asList(
                        new NagPackSuppression.Builder()
                                .id("AwsSolutions-IAM5")
                                .reason("Defined by a default policy")
                                .appliesTo(Arrays.asList(
                                        "Action::s3:Abort*",
                                        "Action::s3:DeleteObject*",
                                        "Action::s3:GetBucket*",
                                        "Action::s3:GetObject*",
                                        "Action::s3:List*",
                                        "Resource::<PipelineArtifactBucketD127CCF6.Arn>/*"
                                ))
                                .build()
                ));

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/RunUnitTests/Role/DefaultPolicy/Resource",
                Arrays.asList(
                        new NagPackSuppression.Builder()
                                .id("AwsSolutions-IAM5")
                                .reason("Defined by a default policy")
                                .appliesTo(Arrays.asList(
                                        "Resource::arn:<AWS::Partition>:logs:<AWS::Region>:<AWS::AccountId>:log-group:/aws/codebuild/<RunUnitTests2AD5FFEA>:*",
                                        "Resource::arn:<AWS::Partition>:codebuild:<AWS::Region>:<AWS::AccountId>:report-group/<RunUnitTests2AD5FFEA>-*",
                                        "Action::s3:GetBucket*",
                                        "Action::s3:GetObject*",
                                        "Action::s3:List*",
                                        "Resource::<PipelineArtifactBucketD127CCF6.Arn>/*"
                                ))
                                .build()
                ));

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/RunUnitTests/Resource",
                Arrays.asList(
                        new NagPackSuppression.Builder()
                                .id("AwsSolutions-CB4")
                                .reason("False-positive. Encryption key is applied")
                                .build()
                ));

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/RunUnitTestsPolicy/Resource",
                Arrays.asList(
                        new NagPackSuppression.Builder()
                                .id("AwsSolutions-IAM5")
                                .reason("Defined by a default policy")
                                .appliesTo(Arrays.asList(
                                        "Resource::*",
                                        "Action::codeartifact:List*"
                                ))
                                .build()
                ));

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/SelfMutate/Role/DefaultPolicy/Resource",
                Arrays.asList(
                        new NagPackSuppression.Builder()
                                .id("AwsSolutions-IAM5")
                                .reason("Defined by a default policy")
                                .appliesTo(Arrays.asList(
                                        "Resource::arn:<AWS::Partition>:logs:<AWS::Region>:<AWS::AccountId>:log-group:/aws/codebuild/<SelfMutate95ADA46F>:*",
                                        "Resource::arn:<AWS::Partition>:codebuild:<AWS::Region>:<AWS::AccountId>:report-group/<SelfMutate95ADA46F>-*",
                                        "Action::s3:GetBucket*",
                                        "Action::s3:GetObject*",
                                        "Action::s3:List*",
                                        "Resource::<PipelineArtifactBucketD127CCF6.Arn>/*"
                                ))
                                .build()
                ));

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/SelfMutate/Resource",
                Arrays.asList(
                        new NagPackSuppression.Builder()
                                .id("AwsSolutions-CB4")
                                .reason("False-positive. Encryption key is applied")
                                .build()
                ));

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/SelfMutatePolicy/Resource",
                Arrays.asList(
                        new NagPackSuppression.Builder()
                                .id("AwsSolutions-IAM5")
                                .reason("Defined by a default policy")
                                .appliesTo(Arrays.asList(
                                        "Resource::*",
                                        "Action::codeartifact:List*",
                                        "Resource::arn:aws:iam::*:role/cdk-*"
                                ))
                                .build()
                ));

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/BuildSamplePackage/sample-package/Role/DefaultPolicy/Resource",
                Arrays.asList(
                        new NagPackSuppression.Builder()
                                .id("AwsSolutions-IAM5")
                                .reason("Defined by a default policy")
                                .appliesTo(Arrays.asList(
                                        "Resource::arn:<AWS::Partition>:logs:<AWS::Region>:<AWS::AccountId>:log-group:/aws/codebuild/<BuildSamplePackagesamplepackageB2962058>:*",
                                        "Resource::arn:<AWS::Partition>:codebuild:<AWS::Region>:<AWS::AccountId>:report-group/<BuildSamplePackagesamplepackageB2962058>-*",
                                        "Action::s3:GetBucket*",
                                        "Action::s3:GetObject*",
                                        "Action::s3:List*",
                                        "Resource::<PipelineArtifactBucketD127CCF6.Arn>/*"
                                ))
                                .build()
                ));

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/BuildSamplePackage/sample-package/Resource",
                Arrays.asList(
                        new NagPackSuppression.Builder()
                                .id("AwsSolutions-CB4")
                                .reason("False-positive. Encryption key is applied")
                                .build()
                ));

        NagSuppressions.addResourceSuppressionsByPath(this,
                "/JavaCdkCicdCodeartifactStack/BuildSamplePackage/PublishPolicy/Resource",
                Arrays.asList(
                        new NagPackSuppression.Builder()
                                .id("AwsSolutions-IAM5")
                                .reason("Defined by a default policy")
                                .appliesTo(Arrays.asList(
                                        "Resource::*",
                                        "Action::codeartifact:List*"
                                ))
                                .build()
                ));
    }
}
