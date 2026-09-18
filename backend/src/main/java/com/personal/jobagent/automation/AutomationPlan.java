package com.personal.jobagent.automation;
import java.util.List; import java.util.Map; import java.util.UUID;
public record AutomationPlan(UUID planId, UUID applicationId, UUID jobId, String targetUrl, List<Step> steps, String safetyContract) {
 public record Step(String id,String type,String policy,Map<String,Object> params) {}
 public static final String SAFETY_CONTRACT="Execute only listed deterministic steps; never bypass CAPTCHA or anti-bot; never invent facts or credentials; stop before submission unless explicitly approved.";
}