package io.priorart.api;

import io.priorart.auth.Identity;
import io.priorart.directory.OnboardingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** The two calls Prior Art skill makes during onboarding. */
@RestController
@RequestMapping("/api")
public class OnboardingController {
    private final OnboardingService onboarding;
    private final Identity identity;

    public OnboardingController(OnboardingService onboarding, Identity identity) {
        this.onboarding = onboarding;
        this.identity = identity;
    }

    /** Who am I, is this repo known, which applications/teams exist. */
    @GetMapping("/lookup")
    public OnboardingService.Lookup lookup(@RequestParam(required = false) String handle,
                                           @RequestParam(required = false) String remoteUrl, HttpServletRequest req) {
        return onboarding.lookup(identity.handle(req, handle), remoteUrl);
    }

    @PostMapping("/onboard")
    public OnboardingService.OnboardResult onboard(@RequestBody OnboardingService.OnboardRequest body, HttpServletRequest req) {
        OnboardingService.OnboardRequest b = new OnboardingService.OnboardRequest(identity.handle(req, body.handle()), body.remoteUrl(),
                body.application(), body.applicationName(), body.team(), body.teamName(), body.role(), body.create());
        return onboarding.onboard(b);
    }
}
