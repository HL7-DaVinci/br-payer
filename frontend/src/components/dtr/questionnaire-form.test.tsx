/// <reference types="vitest/globals" />
import { fireEvent, render, screen } from "@testing-library/react";
import type { Questionnaire, QuestionnaireResponse } from "fhir/r4";
import { createRef } from "react";
import {
  QuestionnaireForm,
  type QuestionnaireFormHandle,
} from "./questionnaire-form";

// --- Initialization from QuestionnaireResponse ---

describe("initialization from QuestionnaireResponse", () => {
  it("pre-populated values appear in the rendered form", () => {
    const q: Questionnaire = {
      resourceType: "Questionnaire",
      status: "active",
      item: [
        { linkId: "1", text: "Name", type: "string" },
        { linkId: "2", text: "Age", type: "integer" },
      ],
    };
    const qr: QuestionnaireResponse = {
      resourceType: "QuestionnaireResponse",
      status: "in-progress",
      item: [
        { linkId: "1", answer: [{ valueString: "John Doe" }] },
        { linkId: "2", answer: [{ valueInteger: 35 }] },
      ],
    };
    render(<QuestionnaireForm questionnaire={q} questionnaireResponse={qr} />);
    expect(screen.getByDisplayValue("John Doe")).toBeInTheDocument();
    expect(screen.getByDisplayValue("35")).toBeInTheDocument();
  });

  it("nested group answers are extracted correctly", () => {
    const q: Questionnaire = {
      resourceType: "Questionnaire",
      status: "active",
      item: [
        {
          linkId: "g1",
          text: "Patient Info",
          type: "group",
          item: [
            { linkId: "g1.1", text: "First name", type: "string" },
            { linkId: "g1.2", text: "Last name", type: "string" },
          ],
        },
      ],
    };
    const qr: QuestionnaireResponse = {
      resourceType: "QuestionnaireResponse",
      status: "in-progress",
      item: [
        {
          linkId: "g1",
          item: [
            { linkId: "g1.1", answer: [{ valueString: "Jane" }] },
            { linkId: "g1.2", answer: [{ valueString: "Smith" }] },
          ],
        },
      ],
    };
    render(<QuestionnaireForm questionnaire={q} questionnaireResponse={qr} />);
    expect(screen.getByDisplayValue("Jane")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Smith")).toBeInTheDocument();
  });
});

// --- QuestionnaireResponse extraction via ref ---

describe("QuestionnaireResponse extraction via ref", () => {
  it("returns valid QR with current answers", () => {
    const q: Questionnaire = {
      resourceType: "Questionnaire",
      status: "active",
      item: [{ linkId: "1", text: "Name", type: "string" }],
    };
    const ref = createRef<QuestionnaireFormHandle>();
    render(<QuestionnaireForm ref={ref} questionnaire={q} />);

    fireEvent.change(screen.getByRole("textbox"), {
      target: { value: "Test" },
    });

    const qr = ref.current?.getQuestionnaireResponse();
    expect(qr?.resourceType).toBe("QuestionnaireResponse");
    expect(qr?.item).toHaveLength(1);
    expect(qr?.item?.[0].linkId).toBe("1");
    expect(qr?.item?.[0].answer?.[0].valueString).toBe("Test");
  });

  it("omits empty items from output", () => {
    const q: Questionnaire = {
      resourceType: "Questionnaire",
      status: "active",
      item: [
        { linkId: "1", text: "Name", type: "string" },
        { linkId: "2", text: "Notes", type: "text" },
      ],
    };
    const ref = createRef<QuestionnaireFormHandle>();
    render(<QuestionnaireForm ref={ref} questionnaire={q} />);

    fireEvent.change(screen.getAllByRole("textbox")[0], {
      target: { value: "Test" },
    });

    const qr = ref.current?.getQuestionnaireResponse();
    expect(qr?.item).toHaveLength(1);
    expect(qr?.item?.[0].linkId).toBe("1");
  });

  it("preserves original QR metadata", () => {
    const q: Questionnaire = {
      resourceType: "Questionnaire",
      status: "active",
      url: "http://example.org/q/1",
      item: [{ linkId: "1", text: "Name", type: "string" }],
    };
    const originalQr: QuestionnaireResponse = {
      resourceType: "QuestionnaireResponse",
      status: "in-progress",
      id: "qr-123",
      meta: { versionId: "1" },
      subject: { reference: "Patient/123" },
      extension: [{ url: "http://example.org/ext", valueString: "test" }],
      contained: [{ resourceType: "Patient", id: "inline-pat" }],
      item: [{ linkId: "1", answer: [{ valueString: "Original" }] }],
    };
    const ref = createRef<QuestionnaireFormHandle>();
    render(
      <QuestionnaireForm
        ref={ref}
        questionnaire={q}
        questionnaireResponse={originalQr}
      />,
    );

    const qr = ref.current?.getQuestionnaireResponse();
    expect(qr?.id).toBe("qr-123");
    expect(qr?.meta?.versionId).toBe("1");
    expect(qr?.subject?.reference).toBe("Patient/123");
    expect(qr?.extension).toHaveLength(1);
    expect(qr?.contained).toHaveLength(1);
  });
});

// --- Adaptive flow (Opioid scenario) ---

describe("adaptive flow - opioid scenario", () => {
  const opioidQuestionnaire: Questionnaire = {
    resourceType: "Questionnaire",
    status: "active",
    item: [
      {
        linkId: "4",
        text: "Risk Assessment",
        type: "group",
        item: [
          {
            linkId: "4.1",
            text: "Is the patient opioid-naive?",
            type: "boolean",
            required: true,
          },
        ],
      },
      {
        linkId: "6",
        text: "Drug Interaction Review",
        type: "group",
        enableWhen: [
          { question: "4.1", operator: "exists", answerBoolean: true },
        ],
        item: [
          {
            linkId: "6.1",
            text: "Active benzodiazepine prescription?",
            type: "boolean",
            required: true,
          },
          {
            linkId: "6.2",
            text: "Risk mitigation plan",
            type: "text",
            enableWhen: [
              { question: "6.1", operator: "=", answerBoolean: true },
            ],
          },
          {
            linkId: "6.3",
            text: "Interacting medications",
            type: "text",
          },
        ],
      },
    ],
  };

  it("initially hides Group 6 when 4.1 not answered", () => {
    render(<QuestionnaireForm questionnaire={opioidQuestionnaire} />);
    expect(screen.getByText("Risk Assessment")).toBeInTheDocument();
    expect(
      screen.queryByText("Drug Interaction Review"),
    ).not.toBeInTheDocument();
  });

  it("shows Group 6 after answering 4.1", () => {
    render(<QuestionnaireForm questionnaire={opioidQuestionnaire} />);
    fireEvent.click(screen.getByRole("checkbox"));
    expect(screen.getByText("Drug Interaction Review")).toBeInTheDocument();
  });

  it("shows item 6.2 only when 6.1 is checked true", () => {
    render(<QuestionnaireForm questionnaire={opioidQuestionnaire} />);

    // Answer 4.1 to reveal Group 6
    fireEvent.click(screen.getByRole("checkbox"));
    expect(screen.getByText("Drug Interaction Review")).toBeInTheDocument();
    expect(screen.queryByText("Risk mitigation plan")).not.toBeInTheDocument();

    // Check 6.1 to reveal 6.2
    const checkboxes = screen.getAllByRole("checkbox");
    fireEvent.click(checkboxes[1]); // 6.1
    expect(screen.getByText("Risk mitigation plan")).toBeInTheDocument();
  });

  it("re-initializes form state when questionnaireResponse prop changes", () => {
    const qr1: QuestionnaireResponse = {
      resourceType: "QuestionnaireResponse",
      status: "in-progress",
      item: [
        {
          linkId: "4",
          item: [{ linkId: "4.1", answer: [{ valueBoolean: true }] }],
        },
      ],
    };
    const qr2: QuestionnaireResponse = {
      resourceType: "QuestionnaireResponse",
      status: "in-progress",
      item: [],
    };

    const { rerender } = render(
      <QuestionnaireForm
        questionnaire={opioidQuestionnaire}
        questionnaireResponse={qr1}
      />,
    );
    expect(screen.getByText("Drug Interaction Review")).toBeInTheDocument();

    rerender(
      <QuestionnaireForm
        questionnaire={opioidQuestionnaire}
        questionnaireResponse={qr2}
      />,
    );
    expect(
      screen.queryByText("Drug Interaction Review"),
    ).not.toBeInTheDocument();
  });
});

// --- Progress Note scenario ---

describe("progress note scenario", () => {
  const progressNoteQ: Questionnaire = {
    resourceType: "Questionnaire",
    status: "active",
    item: [
      {
        linkId: "4",
        text: "Side Effects",
        type: "group",
        item: [
          {
            linkId: "4.2",
            text: "Side effects reported",
            type: "boolean",
          },
          {
            linkId: "4.3",
            text: "Side effect details",
            type: "text",
            enableWhen: [
              { question: "4.2", operator: "=", answerBoolean: true },
            ],
          },
        ],
      },
      {
        linkId: "5",
        text: "Dosage",
        type: "group",
        item: [
          {
            linkId: "5.1",
            text: "Dosage adjustment required",
            type: "boolean",
          },
          {
            linkId: "5.2",
            text: "New dosage",
            type: "string",
            enableWhen: [
              { question: "5.1", operator: "=", answerBoolean: true },
            ],
          },
          {
            linkId: "5.3",
            text: "Reason for adjustment",
            type: "text",
            enableWhen: [
              { question: "5.1", operator: "=", answerBoolean: true },
            ],
          },
        ],
      },
    ],
  };

  it("initially hides conditional items", () => {
    render(<QuestionnaireForm questionnaire={progressNoteQ} />);
    expect(screen.queryByText("Side effect details")).not.toBeInTheDocument();
    expect(screen.queryByText("New dosage")).not.toBeInTheDocument();
    expect(screen.queryByText("Reason for adjustment")).not.toBeInTheDocument();
  });

  it("shows side effect details when checked, hides when unchecked", () => {
    render(<QuestionnaireForm questionnaire={progressNoteQ} />);
    const checkboxes = screen.getAllByRole("checkbox");

    fireEvent.click(checkboxes[0]); // Check "Side effects reported"
    expect(screen.getByText("Side effect details")).toBeInTheDocument();

    fireEvent.click(checkboxes[0]); // Uncheck
    expect(screen.queryByText("Side effect details")).not.toBeInTheDocument();
  });

  it("shows dosage items when dosage adjustment checked", () => {
    render(<QuestionnaireForm questionnaire={progressNoteQ} />);
    const checkboxes = screen.getAllByRole("checkbox");

    fireEvent.click(checkboxes[1]); // Check "Dosage adjustment required"
    expect(screen.getByText("New dosage")).toBeInTheDocument();
    expect(screen.getByText("Reason for adjustment")).toBeInTheDocument();
  });
});
