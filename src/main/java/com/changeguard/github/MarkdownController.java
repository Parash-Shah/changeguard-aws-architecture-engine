package com.changeguard.github;
import com.changeguard.persistence.ReviewService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
public class MarkdownController {
    private final ReviewService reviews;
    public MarkdownController(ReviewService reviews) { this.reviews = reviews; }
    @GetMapping(value="/v1/reviews/{id}/markdown", produces="text/plain")
    public String markdown(@PathVariable UUID id) { return ReviewMarkdown.render(reviews.get(id)); }
}
