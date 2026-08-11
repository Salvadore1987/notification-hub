package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;

/**
 * A request that never reached a controller answers what it is, not 500 (RFC 9110, IR-01).
 *
 * <p>Written against a stub controller rather than a real one on purpose: what is under test is the
 * advice, and the three failures it renders are properties of a mapping — a method it does not have, a
 * media type it does not read, a representation it cannot produce — so the smallest mapping that has
 * them is the honest fixture. The real endpoints of §8.2 would add their codecs and their limiter to a
 * test that is about neither.
 *
 * <p>The 404 branch is deliberately not here: which exception an unmatched path raises
 * ({@code NoResourceFoundException} or {@code NoHandlerFoundException}) is decided by the dispatcher's
 * resource handling, and standalone MockMvc answers 404 without raising either. Asserting it here would
 * prove the fixture rather than the advice.
 */
class UnsupportedRequestHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OnlyJsonController())
                .setControllerAdvice(new UnsupportedRequestHandler(new ProblemFactory()))
                .build();
    }

    @Test
    @DisplayName("RFC 9110 §15.5.6: a wrong method answers 405 and names the ones the path has")
    void answersMethodNotAllowedWithAllow() throws Exception {
        // Arrange + Act + Assert
        mockMvc.perform(delete("/stub"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.detail").value("method DELETE is not supported by this endpoint"));
    }

    @Test
    @DisplayName("IR-01: a body the endpoint cannot read answers 415 and names what it does read")
    void answersUnsupportedMediaTypeWithAccept() throws Exception {
        // Arrange + Act + Assert
        mockMvc.perform(post("/stub").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Accept", org.hamcrest.Matchers.containsString("application/json")))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    /**
     * The body has to survive here, and it is the reason the advice forces the content type: the answer
     * is itself a media type the caller just refused, so negotiation would drop it and leave an empty
     * 406 that says nothing about what went wrong.
     */
    @Test
    @DisplayName("IR-01: an impossible Accept answers 406 that still carries the problem document")
    void answersNotAcceptableWithABody() throws Exception {
        // Arrange + Act + Assert
        mockMvc.perform(get("/stub").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    /** A mapping that reads and writes JSON and nothing else — one GET and one POST. */
    @RestController
    @RequestMapping("/stub")
    static class OnlyJsonController {

        @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
        String read() {
            return "{}";
        }

        @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
        String write(@RequestBody String body) {
            return body;
        }
    }
}
