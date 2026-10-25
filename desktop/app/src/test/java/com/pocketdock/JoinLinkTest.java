package com.pocketdock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JoinLinkTest {
    @Test
    void buildsLinkWithHashFragment() {
        assertEquals("https://a.example.com/#abc.def",
                JoinLink.build("https://a.example.com", "abc", "def"));
    }

    @Test
    void ignoresTrailingSlashAndKeepsPort() {
        assertEquals("http://localhost:8081/#abc.def",
                JoinLink.build("http://localhost:8081/", "abc", "def"));
    }
}
