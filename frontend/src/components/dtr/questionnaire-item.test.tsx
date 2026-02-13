/// <reference types="vitest/globals" />
import { fireEvent, render, screen } from "@testing-library/react";
import type {
  QuestionnaireItem,
  QuestionnaireResponseItemAnswer,
} from "fhir/r4";
import { QuestionnaireItemField } from "./questionnaire-item";

function renderItem(
  item: QuestionnaireItem,
  opts: {
    answers?: QuestionnaireResponseItemAnswer[];
    allAnswers?: Map<string, QuestionnaireResponseItemAnswer[]>;
    readOnly?: boolean;
    onChange?: (
      linkId: string,
      answers: QuestionnaireResponseItemAnswer[],
    ) => void;
  } = {},
) {
  const onChange = opts.onChange ?? vi.fn();
  return {
    onChange,
    ...render(
      <QuestionnaireItemField
        item={item}
        answers={opts.answers ?? []}
        onChange={onChange}
        allAnswers={opts.allAnswers ?? new Map()}
        readOnly={opts.readOnly}
      />,
    ),
  };
}

// --- enableWhen evaluation ---

describe("enableWhen evaluation", () => {
  describe("exists operator", () => {
    const item: QuestionnaireItem = {
      linkId: "6",
      text: "Drug Interaction Review",
      type: "group",
      enableWhen: [
        { question: "4.1", operator: "exists", answerBoolean: true },
      ],
      item: [
        {
          linkId: "6.1",
          text: "Active benzodiazepine?",
          type: "boolean",
        },
      ],
    };

    it("hides item when referenced question has no answer", () => {
      renderItem(item);
      expect(
        screen.queryByText("Drug Interaction Review"),
      ).not.toBeInTheDocument();
    });

    it("shows item when referenced question has an answer", () => {
      const allAnswers = new Map([["4.1", [{ valueBoolean: false }]]]);
      renderItem(item, { allAnswers });
      expect(screen.getByText("Drug Interaction Review")).toBeInTheDocument();
    });
  });

  describe("= operator with boolean", () => {
    const item: QuestionnaireItem = {
      linkId: "6.2",
      text: "Risk mitigation plan",
      type: "text",
      enableWhen: [{ question: "6.1", operator: "=", answerBoolean: true }],
    };

    it("hides item when answer is false", () => {
      const allAnswers = new Map([["6.1", [{ valueBoolean: false }]]]);
      renderItem(item, { allAnswers });
      expect(
        screen.queryByText("Risk mitigation plan"),
      ).not.toBeInTheDocument();
    });

    it("shows item when answer matches true", () => {
      const allAnswers = new Map([["6.1", [{ valueBoolean: true }]]]);
      renderItem(item, { allAnswers });
      expect(screen.getByText("Risk mitigation plan")).toBeInTheDocument();
    });

    it("hides item when no answer exists", () => {
      renderItem(item);
      expect(
        screen.queryByText("Risk mitigation plan"),
      ).not.toBeInTheDocument();
    });
  });

  describe("= operator with coding", () => {
    const item: QuestionnaireItem = {
      linkId: "c1",
      text: "Follow-up details",
      type: "text",
      enableWhen: [
        {
          question: "status",
          operator: "=",
          answerCoding: { system: "http://example.org", code: "active" },
        },
      ],
    };

    it("shows when valueCoding.code matches answerCoding.code", () => {
      const allAnswers = new Map([
        [
          "status",
          [
            {
              valueCoding: {
                system: "http://example.org",
                code: "active",
              },
            },
          ],
        ],
      ]);
      renderItem(item, { allAnswers });
      expect(screen.getByText("Follow-up details")).toBeInTheDocument();
    });

    it("hides when valueCoding.code does not match", () => {
      const allAnswers = new Map([
        [
          "status",
          [
            {
              valueCoding: {
                system: "http://example.org",
                code: "inactive",
              },
            },
          ],
        ],
      ]);
      renderItem(item, { allAnswers });
      expect(screen.queryByText("Follow-up details")).not.toBeInTheDocument();
    });
  });

  describe("!= operator", () => {
    const item: QuestionnaireItem = {
      linkId: "ne1",
      text: "Non-standard note",
      type: "text",
      enableWhen: [{ question: "q1", operator: "!=", answerBoolean: true }],
    };

    it("shows when answer does not match", () => {
      const allAnswers = new Map([["q1", [{ valueBoolean: false }]]]);
      renderItem(item, { allAnswers });
      expect(screen.getByText("Non-standard note")).toBeInTheDocument();
    });

    it("hides when answer matches", () => {
      const allAnswers = new Map([["q1", [{ valueBoolean: true }]]]);
      renderItem(item, { allAnswers });
      expect(screen.queryByText("Non-standard note")).not.toBeInTheDocument();
    });

    it("shows when no answer exists", () => {
      renderItem(item);
      expect(screen.getByText("Non-standard note")).toBeInTheDocument();
    });
  });

  describe("enableBehavior", () => {
    it("shows with 'any' when at least one condition is met", () => {
      const item: QuestionnaireItem = {
        linkId: "any1",
        text: "Any condition met",
        type: "text",
        enableBehavior: "any",
        enableWhen: [
          { question: "a", operator: "=", answerBoolean: true },
          { question: "b", operator: "=", answerBoolean: true },
        ],
      };
      const allAnswers = new Map([
        ["a", [{ valueBoolean: true }]],
        ["b", [{ valueBoolean: false }]],
      ]);
      renderItem(item, { allAnswers });
      expect(screen.getByText("Any condition met")).toBeInTheDocument();
    });

    it("hides with default 'all' when any condition fails", () => {
      const item: QuestionnaireItem = {
        linkId: "all1",
        text: "All conditions required",
        type: "text",
        enableWhen: [
          { question: "a", operator: "=", answerBoolean: true },
          { question: "b", operator: "=", answerBoolean: true },
        ],
      };
      const allAnswers = new Map([
        ["a", [{ valueBoolean: true }]],
        ["b", [{ valueBoolean: false }]],
      ]);
      renderItem(item, { allAnswers });
      expect(
        screen.queryByText("All conditions required"),
      ).not.toBeInTheDocument();
    });

    it("shows with 'all' when every condition is met", () => {
      const item: QuestionnaireItem = {
        linkId: "all2",
        text: "All conditions met",
        type: "text",
        enableWhen: [
          { question: "a", operator: "=", answerBoolean: true },
          { question: "b", operator: "=", answerBoolean: true },
        ],
      };
      const allAnswers = new Map([
        ["a", [{ valueBoolean: true }]],
        ["b", [{ valueBoolean: true }]],
      ]);
      renderItem(item, { allAnswers });
      expect(screen.getByText("All conditions met")).toBeInTheDocument();
    });
  });

  it("always renders item without enableWhen", () => {
    const item: QuestionnaireItem = {
      linkId: "simple",
      text: "Simple question",
      type: "string",
    };
    renderItem(item);
    expect(screen.getByText("Simple question")).toBeInTheDocument();
  });
});

// --- Item type rendering ---

describe("item type rendering", () => {
  it("string renders input with onChange producing valueString", () => {
    const item: QuestionnaireItem = {
      linkId: "s1",
      text: "Name",
      type: "string",
    };
    const { onChange } = renderItem(item);
    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "John" } });
    expect(onChange).toHaveBeenCalledWith("s1", [{ valueString: "John" }]);
  });

  it("text renders textarea", () => {
    const item: QuestionnaireItem = {
      linkId: "t1",
      text: "Notes",
      type: "text",
    };
    const { onChange } = renderItem(item);
    const textarea = screen.getByRole("textbox");
    expect(textarea.tagName).toBe("TEXTAREA");
    fireEvent.change(textarea, { target: { value: "Some notes" } });
    expect(onChange).toHaveBeenCalledWith("t1", [
      { valueString: "Some notes" },
    ]);
  });

  it("boolean renders checkbox with onChange producing valueBoolean", () => {
    const item: QuestionnaireItem = {
      linkId: "b1",
      text: "Is active?",
      type: "boolean",
    };
    const { onChange } = renderItem(item);
    const checkbox = screen.getByRole("checkbox");
    fireEvent.click(checkbox);
    expect(onChange).toHaveBeenCalledWith("b1", [{ valueBoolean: true }]);
  });

  it("date renders date input with onChange producing valueDate", () => {
    const item: QuestionnaireItem = {
      linkId: "d1",
      text: "Birth date",
      type: "date",
    };
    const { onChange } = renderItem(item);
    const input = document.querySelector('input[type="date"]');
    expect(input).not.toBeNull();
    fireEvent.change(input as Element, { target: { value: "2024-01-15" } });
    expect(onChange).toHaveBeenCalledWith("d1", [{ valueDate: "2024-01-15" }]);
  });

  it("integer renders number input with step=1", () => {
    const item: QuestionnaireItem = {
      linkId: "i1",
      text: "Age",
      type: "integer",
    };
    const { onChange } = renderItem(item);
    const input = screen.getByRole("spinbutton");
    expect(input).toHaveAttribute("step", "1");
    fireEvent.change(input, { target: { value: "42" } });
    expect(onChange).toHaveBeenCalledWith("i1", [{ valueInteger: 42 }]);
  });

  it("decimal renders number input with step=any and respects min/max extensions", () => {
    const item: QuestionnaireItem = {
      linkId: "dec1",
      text: "Temperature",
      type: "decimal",
      extension: [
        {
          url: "http://hl7.org/fhir/StructureDefinition/minValue",
          valueDecimal: 0,
        },
        {
          url: "http://hl7.org/fhir/StructureDefinition/maxValue",
          valueDecimal: 100,
        },
      ],
    };
    const { onChange } = renderItem(item);
    const input = screen.getByRole("spinbutton");
    expect(input).toHaveAttribute("step", "any");
    expect(input).toHaveAttribute("min", "0");
    expect(input).toHaveAttribute("max", "100");
    fireEvent.change(input, { target: { value: "37.5" } });
    expect(onChange).toHaveBeenCalledWith("dec1", [{ valueDecimal: 37.5 }]);
  });

  it("choice with 3 options renders RadioGroup with 3 radio buttons", () => {
    const item: QuestionnaireItem = {
      linkId: "ch1",
      text: "Severity",
      type: "choice",
      answerOption: [
        { valueCoding: { code: "mild", display: "Mild" } },
        { valueCoding: { code: "moderate", display: "Moderate" } },
        { valueCoding: { code: "severe", display: "Severe" } },
      ],
    };
    const { onChange } = renderItem(item);
    const radios = screen.getAllByRole("radio");
    expect(radios).toHaveLength(3);
    expect(screen.getByText("Mild")).toBeInTheDocument();
    expect(screen.getByText("Moderate")).toBeInTheDocument();
    expect(screen.getByText("Severe")).toBeInTheDocument();

    fireEvent.click(radios[1]);
    expect(onChange).toHaveBeenCalledWith("ch1", [
      { valueCoding: { code: "moderate", display: "Moderate" } },
    ]);
  });

  it("choice with repeats renders checkboxes for multi-select", () => {
    const item: QuestionnaireItem = {
      linkId: "ch2",
      text: "Symptoms",
      type: "choice",
      repeats: true,
      answerOption: [
        { valueCoding: { code: "pain", display: "Pain" } },
        { valueCoding: { code: "nausea", display: "Nausea" } },
        { valueCoding: { code: "fatigue", display: "Fatigue" } },
      ],
    };
    const { onChange } = renderItem(item);
    const checkboxes = screen.getAllByRole("checkbox");
    expect(checkboxes).toHaveLength(3);

    fireEvent.click(checkboxes[0]);
    expect(onChange).toHaveBeenCalledWith("ch2", [
      { valueCoding: { code: "pain", display: "Pain" } },
    ]);
  });

  it("group renders Card with children recursively", () => {
    const item: QuestionnaireItem = {
      linkId: "g1",
      text: "Patient Info",
      type: "group",
      item: [
        { linkId: "g1.1", text: "First name", type: "string" },
        { linkId: "g1.2", text: "Last name", type: "string" },
      ],
    };
    renderItem(item);
    expect(screen.getByText("Patient Info")).toBeInTheDocument();
    expect(screen.getByText("First name")).toBeInTheDocument();
    expect(screen.getByText("Last name")).toBeInTheDocument();
  });

  it("display renders text paragraph", () => {
    const item: QuestionnaireItem = {
      linkId: "disp1",
      text: "Please read carefully before proceeding.",
      type: "display",
    };
    renderItem(item);
    expect(
      screen.getByText("Please read carefully before proceeding."),
    ).toBeInTheDocument();
  });

  it("unsupported type renders fallback message", () => {
    const item = {
      linkId: "u1",
      text: "Reference field",
      type: "reference",
    } as QuestionnaireItem;
    renderItem(item);
    expect(screen.getByText(/Unsupported item type/)).toBeInTheDocument();
    expect(screen.getByText("reference")).toBeInTheDocument();
  });
});

// --- Read-only behavior ---

describe("read-only behavior", () => {
  it("readOnly on item disables input", () => {
    const item: QuestionnaireItem = {
      linkId: "ro1",
      text: "Read-only field",
      type: "string",
      readOnly: true,
    };
    renderItem(item);
    expect(screen.getByRole("textbox")).toBeDisabled();
  });

  it("readOnly prop from parent disables input", () => {
    const item: QuestionnaireItem = {
      linkId: "ro2",
      text: "Parent read-only",
      type: "string",
    };
    renderItem(item, { readOnly: true });
    expect(screen.getByRole("textbox")).toBeDisabled();
  });
});
