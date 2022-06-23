// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.myorg;

 import software.amazon.awscdk.App;
 import software.amazon.awscdk.assertions.Template;
 import java.io.IOException;

 import java.util.HashMap;

 import org.junit.jupiter.api.Test;

 public class JavaCdkCicdCodeartifactTest {

     @Test
     public void testStack() throws IOException {
         App app = new App();
         JavaCdkCicdCodeartifactStack stack = new JavaCdkCicdCodeartifactStack(app, "JavaCdkCicdCodeartifactStack");

         Template template = Template.fromStack(stack);

         template.hasResourceProperties("AWS::CodeCommit::Repository", new HashMap<String, String>() {{
           put("RepositoryName", "JavaSampleRepository");
         }});
     }
 }
