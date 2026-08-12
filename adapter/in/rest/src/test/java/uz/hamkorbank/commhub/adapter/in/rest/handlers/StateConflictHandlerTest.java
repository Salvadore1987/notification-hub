package uz.hamkorbank.commhub.adapter.in.rest.handlers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.domain.exception.InvalidStatusTransitionException;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;

/**
 * Both ways a request can contradict stored state answer 409, and say which (FR-3.2, FR-2.1).
 *
 * <p>The configuration branch is the one worth a test of its own: it had no handler at all, so every
 * conflict the administration raises — a stream registered twice, a template with nothing published in
 * the locale the panel asked for — fell through to the catch-all and came back as 500. That is not a
 * cosmetic difference: 500 tells an operator the Hub is broken and tells a retry it might work, when the
 * only thing that will change the answer is the operator fixing what they typed.
 */
class StateConflictHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ConflictingController())
                .setControllerAdvice(new StateConflictHandler(new ProblemFactory()))
                .build();
    }

    @Test
    @DisplayName("FR-3.2: a refused status transition answers 409 with the transition spelled out")
    void rendersARefusedTransition() throws Exception {
        // Arrange + Act + Assert
        mockMvc.perform(get("/stub/transition"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("DELIVERED")));
    }

    @Test
    @DisplayName("FR-2.1: a configuration conflict answers 409 and its message, never 500")
    void rendersAConfigurationConflict() throws Exception {
        // Arrange + Act + Assert
        mockMvc.perform(get("/stub/configuration"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.detail").value("template GREATING has no published version in RU"));
    }

    /** The smallest mapping that raises each of the two: what is under test is the advice. */
    @RestController
    @RequestMapping("/stub")
    static class ConflictingController {

        @GetMapping("/transition")
        String transition() {
            throw InvalidStatusTransitionException.of(
                    "message", MessageStatus.DELIVERED, MessageStatus.SENT_TO_PROVIDER);
        }

        @GetMapping("/configuration")
        String configuration() {
            throw new ConfigurationConflictException("template GREATING has no published version in RU");
        }
    }
}
