/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.java.quarkus.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import software.amazon.smithy.java.quarkus.runtime.SmithyServerLifecycle;

/**
 * Quarkus {@code @BuildStep} processors for the experimental Smithy-Java extension.
 *
 * <p>Two responsibilities, both small:
 * <ol>
 *   <li>Advertise a Quarkus feature so the standard banner shows
 *       {@code Installed features: [smithy]} on boot.</li>
 *   <li>Make {@link SmithyServerLifecycle} an unremovable CDI bean. Without
 *       this, Arc's bean removal would prune the lifecycle observer because no
 *       application code injects it directly.</li>
 * </ol>
 *
 * <p>The codegen integration is not a {@code @BuildStep} — it is a
 * {@code CodeGenProvider} discovered via Java SPI. See
 * {@link SmithyCodeGenProvider}.
 */
public final class SmithyProcessor {

    private static final String FEATURE = "smithy";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem registerLifecycleBean() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClass(SmithyServerLifecycle.class)
                .setUnremovable()
                .build();
    }
}
