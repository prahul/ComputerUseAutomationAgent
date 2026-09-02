package com.example.cua.tenant;

import com.example.cua.artifact.Artifact;
import com.example.cua.core.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Binds a tenant-agnostic {@link Artifact} to one institution's deployment of the shared vendor
 * product. This is how "hundreds of tenants running the same app" reuse one recording instead of
 * re-recording per tenant: the artifact stays canonical, the profile supplies the base URL and a
 * small set of per-step locator overrides for the places a tenant's branding/config diverges.
 */
public record TenantProfile(
        String tenantId,
        String vendorProduct,     // must match Artifact.target.vendorProduct
        String baseUrl,           // substituted for ${tenant.baseUrl}
        Map<String, String> secrets,
        /** stepId -> replacement LocatorSpec (e.g. tenant renamed the "Open Sub-Account" button). */
        Map<String, Artifact.LocatorSpec> stepLocatorOverrides,
        /** stepId -> replacement checkpoint/expected text, for label differences. */
        Map<String, String> textOverrides,
        List<String> notes
) {
    public static TenantProfile load(Path file) {
        return Json.readFile(file, TenantProfile.class);
    }

    public static TenantProfile loadById(Path configDir, String tenantId) {
        Path f = configDir.resolve("tenants").resolve(tenantId + ".json");
        if (!Files.exists(f)) {
            throw new IllegalArgumentException("no tenant profile: " + f);
        }
        return load(f);
    }

    /** Returns the effective locator for a step: the override if present, else the artifact's own. */
    public Artifact.LocatorSpec locatorFor(Artifact.Step step) {
        Artifact.LocatorSpec override = stepLocatorOverrides == null ? null : stepLocatorOverrides.get(step.id());
        return override != null ? override : step.target();
    }

    public String resolveUrl(String url) {
        return url == null ? null : url.replace("${tenant.baseUrl}", baseUrl);
    }
}
