package com.personal.jobagent.system;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Adds the commit Render actually deployed to {@code /actuator/info}.
 *
 * <p>The Docker build context is {@code backend/}, so the git-commit-id plugin
 * never sees {@code .git} and the standard {@code git} section is always empty
 * in production. Render sets {@code RENDER_GIT_COMMIT} and
 * {@code RENDER_GIT_BRANCH} at runtime for every deploy, which makes the running
 * commit checkable against {@code origin/master} without dashboard access. Both
 * values are public for this repository; nothing else from the environment is
 * read or exposed, and values that do not look like a commit or branch name are
 * ignored rather than echoed.
 */
@Component
public class DeploymentInfoContributor implements InfoContributor {

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,100}");

    private final UnaryOperator<String> environment;

    @Autowired
    public DeploymentInfoContributor() {
        this(System::getenv);
    }

    DeploymentInfoContributor(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    @Override
    public void contribute(Info.Builder builder) {
        String commit = environment.apply("RENDER_GIT_COMMIT");
        if (commit == null || !COMMIT.matcher(commit).matches()) {
            return;
        }
        Map<String, String> deployment = new LinkedHashMap<>();
        deployment.put("platform", "render");
        deployment.put("commit", commit);
        String branch = environment.apply("RENDER_GIT_BRANCH");
        if (branch != null && BRANCH.matcher(branch).matches()) {
            deployment.put("branch", branch);
        }
        builder.withDetail("deployment", deployment);
    }
}
