package org.hl7.davinci.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves MkDocs-generated documentation under /docs/.
 */
@Controller
public class DocsController {

    @GetMapping("/docs")
    public String rootRedirect() {
        return "redirect:/docs/";
    }

    @GetMapping("/docs/")
    public String root() {
        return "forward:/docs/index.html";
    }

    @GetMapping("/docs/{page:[^\\\\.]+}")
    public String pageRedirect(@PathVariable("page") String page) {
        return "redirect:/docs/" + page + "/";
    }

    @GetMapping("/docs/{page:[^\\\\.]+}/")
    public String page(@PathVariable("page") String page) {
        return "forward:/docs/" + page + "/index.html";
    }

    @GetMapping("/docs/{section:[^\\\\.]+}/{page:[^\\\\.]+}")
    public String subPageRedirect(@PathVariable("section") String section, @PathVariable("page") String page) {
        return "redirect:/docs/" + section + "/" + page + "/";
    }

    @GetMapping("/docs/{section:[^\\\\.]+}/{page:[^\\\\.]+}/")
    public String subPage(@PathVariable("section") String section, @PathVariable("page") String page) {
        return "forward:/docs/" + section + "/" + page + "/index.html";
    }
}
