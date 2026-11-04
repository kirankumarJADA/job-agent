package com.personal.jobagent.system;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentInfoContributorTest {

    private static final String SHA = "484c5c5d17f5b17c1f27067b871d6f3ab491a9cd";

    @Test
    void exposesTheDeployedCommitAndBranchWhenRenderProvidesThem() {
        Map<String, Object> details = contribute(Map.of(
                "RENDER_GIT_COMMIT", SHA,
                "RENDER_GIT_BRANCH", "master"));

        assertThat(details).containsKey("deployment");
        @SuppressWarnings("unchecked")
        Map<String, String> deployment = (Map<String, String>) details.get("deployment");
        assertThat(deployment)
                .containsEntry("platform", "render")
                .containsEntry("commit", SHA)
                .containsEntry("branch", "master");
    }

    @Test
    void contributesNothingOutsideRender() {
        assertThat(contribute(Map.of())).doesNotContainKey("deployment");
    }

    @Test
    void ignoresAValueThatIsNotACommitSha() {
        assertThat(contribute(Map.of("RENDER_GIT_COMMIT", "not-a-sha <script>")))
                .doesNotContainKey("deployment");
    }

    @Test
    void omitsAnImplausibleBranchButKeepsTheCommit() {
        Map<String, Object> details = contribute(Map.of(
                "RENDER_GIT_COMMIT", SHA,
                "RENDER_GIT_BRANCH", "master; rm -rf /"));

        @SuppressWarnings("unchecked")
        Map<String, String> deployment = (Map<String, String>) details.get("deployment");
        assertThat(deployment).containsEntry("commit", SHA).doesNotContainKey("branch");
    }

    private static Map<String, Object> contribute(Map<String, String> env) {
        Info.Builder builder = new Info.Builder();
        new DeploymentInfoContributor(env::get).contribute(builder);
        return builder.build().getDetails();
    }
}
