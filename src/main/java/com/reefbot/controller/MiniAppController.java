package com.reefbot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves Mini App index pages — forwards directory requests to index.html
 * (Spring Boot does not auto-serve index.html for sub-paths).
 */
@Controller
public class MiniAppController {

    @GetMapping({"/mini/plinko", "/mini/plinko/"})
    public String plinkoIndex() {
        return "forward:/mini/plinko/index.html";
    }

    @GetMapping({"/mini/slot", "/mini/slot/"})
    public String slotIndex() {
        return "forward:/mini/slot/index.html";
    }
}
