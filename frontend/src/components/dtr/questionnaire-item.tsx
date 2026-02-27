import type {
  QuestionnaireItem,
  QuestionnaireItemAnswerOption,
  QuestionnaireResponseItemAnswer,
} from "fhir/r4";
import { Plus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NumberInput } from "@/components/ui/number-input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";

export interface ItemProps {
  item: QuestionnaireItem;
  answers: QuestionnaireResponseItemAnswer[];
  onChange: (
    linkId: string,
    answers: QuestionnaireResponseItemAnswer[],
  ) => void;
  allAnswers: Map<string, QuestionnaireResponseItemAnswer[]>;
  readOnly?: boolean;
}

// --- enableWhen evaluation ---

function evaluateEnableWhen(
  item: QuestionnaireItem,
  allAnswers: Map<string, QuestionnaireResponseItemAnswer[]>,
): boolean {
  if (!item.enableWhen?.length) return true;

  const results = item.enableWhen.map((condition) => {
    const answers = allAnswers.get(condition.question) ?? [];

    if (condition.operator === "exists") {
      const shouldExist = condition.answerBoolean ?? true;
      return shouldExist ? answers.length > 0 : answers.length === 0;
    }

    if (condition.operator === "=") {
      return answers.some((a) => {
        if (condition.answerCoding) {
          return a.valueCoding?.code === condition.answerCoding.code;
        }
        if (condition.answerBoolean !== undefined) {
          return a.valueBoolean === condition.answerBoolean;
        }
        if (condition.answerString !== undefined) {
          return a.valueString === condition.answerString;
        }
        if (condition.answerInteger !== undefined) {
          return a.valueInteger === condition.answerInteger;
        }
        if (condition.answerDecimal !== undefined) {
          return a.valueDecimal === condition.answerDecimal;
        }
        return false;
      });
    }

    if (condition.operator === "!=") {
      if (answers.length === 0) return true;
      return answers.every((a) => {
        if (condition.answerCoding) {
          return a.valueCoding?.code !== condition.answerCoding.code;
        }
        if (condition.answerBoolean !== undefined) {
          return a.valueBoolean !== condition.answerBoolean;
        }
        if (condition.answerString !== undefined) {
          return a.valueString !== condition.answerString;
        }
        if (condition.answerInteger !== undefined) {
          return a.valueInteger !== condition.answerInteger;
        }
        return true;
      });
    }

    return true;
  });

  const behavior = item.enableBehavior ?? "all";
  return behavior === "any" ? results.some(Boolean) : results.every(Boolean);
}

// --- Dispatcher ---

export function QuestionnaireItemField(props: ItemProps) {
  if (!evaluateEnableWhen(props.item, props.allAnswers)) return null;

  switch (props.item.type) {
    case "group":
      return <GroupItem {...props} />;
    case "display":
      return <DisplayItem {...props} />;
    case "string":
      return <StringItem {...props} />;
    case "text":
      return <TextItem {...props} />;
    case "boolean":
      return <BooleanItem {...props} />;
    case "date":
      return <DateItem {...props} />;
    case "integer":
      return <IntegerItem {...props} />;
    case "decimal":
      return <DecimalItem {...props} />;
    case "quantity":
      return <QuantityItem {...props} />;
    case "choice":
    case "open-choice":
      return <ChoiceItem {...props} />;
    default:
      return <UnsupportedItem {...props} />;
  }
}

// --- Item wrapper for label + field ---

function FieldWrapper({
  item,
  children,
}: {
  item: QuestionnaireItem;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      {item.text && (
        <Label className="text-sm">
          {item.text}
          {item.required && <span className="text-destructive ml-0.5">*</span>}
        </Label>
      )}
      {children}
    </div>
  );
}

// --- Individual item types ---

function GroupItem({ item, onChange, allAnswers, readOnly }: ItemProps) {
  return (
    <Card className="py-4">
      {item.text && (
        <CardHeader className="py-0">
          <CardTitle className="text-base">{item.text}</CardTitle>
        </CardHeader>
      )}
      <CardContent className="space-y-4 py-0">
        {item.item?.map((child) => (
          <QuestionnaireItemField
            key={child.linkId}
            item={child}
            answers={allAnswers.get(child.linkId) ?? []}
            onChange={onChange}
            allAnswers={allAnswers}
            readOnly={readOnly}
          />
        ))}
      </CardContent>
    </Card>
  );
}

function DisplayItem({ item }: ItemProps) {
  if (!item.text) return null;
  return <p className="text-sm text-muted-foreground">{item.text}</p>;
}

function StringItem({ item, answers, onChange, readOnly }: ItemProps) {
  const value = answers[0]?.valueString ?? "";
  return (
    <FieldWrapper item={item}>
      <Input
        value={value}
        onChange={(e) =>
          onChange(
            item.linkId,
            e.target.value ? [{ valueString: e.target.value }] : [],
          )
        }
        disabled={readOnly || item.readOnly}
      />
    </FieldWrapper>
  );
}

function TextItem({ item, answers, onChange, readOnly }: ItemProps) {
  const value = answers[0]?.valueString ?? "";
  return (
    <FieldWrapper item={item}>
      <Textarea
        value={value}
        onChange={(e) =>
          onChange(
            item.linkId,
            e.target.value ? [{ valueString: e.target.value }] : [],
          )
        }
        disabled={readOnly || item.readOnly}
      />
    </FieldWrapper>
  );
}

function BooleanItem({ item, answers, onChange, readOnly }: ItemProps) {
  const checked = answers[0]?.valueBoolean ?? false;
  return (
    <div className="flex items-center gap-2">
      <Checkbox
        id={item.linkId}
        checked={checked}
        onCheckedChange={(val) =>
          onChange(item.linkId, [{ valueBoolean: val === true }])
        }
        disabled={readOnly || item.readOnly}
      />
      {item.text && (
        <Label htmlFor={item.linkId} className="text-sm cursor-pointer">
          {item.text}
          {item.required && <span className="text-destructive ml-0.5">*</span>}
        </Label>
      )}
    </div>
  );
}

function DateItem({ item, answers, onChange, readOnly }: ItemProps) {
  const value = answers[0]?.valueDate ?? "";
  return (
    <FieldWrapper item={item}>
      <Input
        type="date"
        value={value}
        onChange={(e) =>
          onChange(
            item.linkId,
            e.target.value ? [{ valueDate: e.target.value }] : [],
          )
        }
        disabled={readOnly || item.readOnly}
        className="w-fit"
      />
    </FieldWrapper>
  );
}

function IntegerItem({ item, answers, onChange, readOnly }: ItemProps) {
  const value = answers[0]?.valueInteger;
  return (
    <FieldWrapper item={item}>
      <NumberInput
        step="1"
        value={value ?? ""}
        onChange={(e) => {
          const parsed = Number.parseInt(
            (e.target as HTMLInputElement).value,
            10,
          );
          onChange(
            item.linkId,
            Number.isNaN(parsed) ? [] : [{ valueInteger: parsed }],
          );
        }}
        disabled={readOnly || item.readOnly}
        className="w-fit"
      />
    </FieldWrapper>
  );
}

function DecimalItem({ item, answers, onChange, readOnly }: ItemProps) {
  const value = answers[0]?.valueDecimal;
  const minExt = item.extension?.find(
    (e) => e.url === "http://hl7.org/fhir/StructureDefinition/minValue",
  );
  const maxExt = item.extension?.find(
    (e) => e.url === "http://hl7.org/fhir/StructureDefinition/maxValue",
  );

  return (
    <FieldWrapper item={item}>
      <NumberInput
        step="any"
        min={minExt?.valueDecimal}
        max={maxExt?.valueDecimal}
        value={value ?? ""}
        onChange={(e) => {
          const parsed = Number.parseFloat(
            (e.target as HTMLInputElement).value,
          );
          onChange(
            item.linkId,
            Number.isNaN(parsed) ? [] : [{ valueDecimal: parsed }],
          );
        }}
        disabled={readOnly || item.readOnly}
        className="w-fit"
      />
    </FieldWrapper>
  );
}

function QuantityItem({ item, answers, onChange, readOnly }: ItemProps) {
  const qty = answers[0]?.valueQuantity;
  const unitExt = item.extension?.find(
    (e) =>
      e.url === "http://hl7.org/fhir/StructureDefinition/questionnaire-unit",
  );
  const unit =
    unitExt?.valueCoding?.display ?? unitExt?.valueCoding?.code ?? "";

  return (
    <FieldWrapper item={item}>
      <div className="flex items-center gap-2">
        <NumberInput
          step="any"
          value={qty?.value ?? ""}
          onChange={(e) => {
            const parsed = Number.parseFloat(
              (e.target as HTMLInputElement).value,
            );
            onChange(
              item.linkId,
              Number.isNaN(parsed)
                ? []
                : [
                    {
                      valueQuantity: {
                        value: parsed,
                        unit: unit || undefined,
                        system: unitExt?.valueCoding?.system,
                        code: unitExt?.valueCoding?.code,
                      },
                    },
                  ],
            );
          }}
          disabled={readOnly || item.readOnly}
          className="w-fit"
        />
        {unit && <span className="text-sm text-muted-foreground">{unit}</span>}
      </div>
    </FieldWrapper>
  );
}

function getOptionDisplay(opt: QuestionnaireItemAnswerOption): string {
  if (opt.valueCoding)
    return opt.valueCoding.display ?? opt.valueCoding.code ?? "";
  if (opt.valueString) return opt.valueString;
  if (opt.valueInteger !== undefined) return String(opt.valueInteger);
  return "Unknown";
}

function getOptionValue(opt: QuestionnaireItemAnswerOption): string {
  if (opt.valueCoding) return opt.valueCoding.code ?? "";
  if (opt.valueString) return opt.valueString;
  if (opt.valueInteger !== undefined) return String(opt.valueInteger);
  return "";
}

function optionToAnswer(
  opt: QuestionnaireItemAnswerOption,
): QuestionnaireResponseItemAnswer {
  if (opt.valueCoding) return { valueCoding: opt.valueCoding };
  if (opt.valueString) return { valueString: opt.valueString };
  if (opt.valueInteger !== undefined) return { valueInteger: opt.valueInteger };
  return {};
}

function ChoiceItem({ item, answers, onChange, readOnly }: ItemProps) {
  const options = item.answerOption ?? [];
  const isOpenChoice = item.type === "open-choice";
  const isRepeating = item.repeats === true;
  const disabled = readOnly || item.readOnly;

  // Repeating choice uses checkboxes
  if (isRepeating) {
    const selectedCodes = new Set(
      answers
        .map((a) => a.valueCoding?.code ?? a.valueString ?? "")
        .filter(Boolean),
    );

    const handleToggle = (
      opt: QuestionnaireItemAnswerOption,
      checked: boolean,
    ) => {
      const code = getOptionValue(opt);
      let next: QuestionnaireResponseItemAnswer[];
      if (checked) {
        next = [...answers, optionToAnswer(opt)];
      } else {
        next = answers.filter((a) => {
          const aCode = a.valueCoding?.code ?? a.valueString ?? "";
          return aCode !== code;
        });
      }
      onChange(item.linkId, next);
    };

    return (
      <FieldWrapper item={item}>
        <div className="space-y-2">
          {options.map((opt) => {
            const code = getOptionValue(opt);
            return (
              <div key={code} className="flex items-center gap-2">
                <Checkbox
                  id={`${item.linkId}-${code}`}
                  checked={selectedCodes.has(code)}
                  onCheckedChange={(val) => handleToggle(opt, val === true)}
                  disabled={disabled}
                />
                <Label
                  htmlFor={`${item.linkId}-${code}`}
                  className="text-sm cursor-pointer font-normal"
                >
                  {getOptionDisplay(opt)}
                </Label>
              </div>
            );
          })}
          {isOpenChoice && (
            <OpenChoiceOtherInput
              item={item}
              answers={answers}
              onChange={onChange}
              options={options}
              disabled={disabled}
            />
          )}
        </div>
      </FieldWrapper>
    );
  }

  // Non-repeating: RadioGroup for <= 7 options, Select for more
  const selectedCode =
    answers[0]?.valueCoding?.code ?? answers[0]?.valueString ?? "";

  if (options.length <= 7) {
    return (
      <FieldWrapper item={item}>
        <RadioGroup
          value={selectedCode}
          onValueChange={(val) => {
            const opt = options.find((o) => getOptionValue(o) === val);
            if (opt) onChange(item.linkId, [optionToAnswer(opt)]);
          }}
          disabled={disabled}
        >
          {options.map((opt) => {
            const code = getOptionValue(opt);
            return (
              <div key={code} className="flex items-center gap-2">
                <RadioGroupItem value={code} id={`${item.linkId}-${code}`} />
                <Label
                  htmlFor={`${item.linkId}-${code}`}
                  className="text-sm cursor-pointer font-normal"
                >
                  {getOptionDisplay(opt)}
                </Label>
              </div>
            );
          })}
        </RadioGroup>
        {isOpenChoice && (
          <OpenChoiceOtherInput
            item={item}
            answers={answers}
            onChange={onChange}
            options={options}
            disabled={disabled}
          />
        )}
      </FieldWrapper>
    );
  }

  return (
    <FieldWrapper item={item}>
      <Select
        value={selectedCode}
        onValueChange={(val) => {
          const opt = options.find((o) => getOptionValue(o) === val);
          if (opt) onChange(item.linkId, [optionToAnswer(opt)]);
        }}
        disabled={disabled}
      >
        <SelectTrigger>
          <SelectValue placeholder="Select..." />
        </SelectTrigger>
        <SelectContent>
          {options.map((opt) => {
            const code = getOptionValue(opt);
            return (
              <SelectItem key={code} value={code}>
                {getOptionDisplay(opt)}
              </SelectItem>
            );
          })}
        </SelectContent>
      </Select>
      {isOpenChoice && (
        <OpenChoiceOtherInput
          item={item}
          answers={answers}
          onChange={onChange}
          options={options}
          disabled={disabled}
        />
      )}
    </FieldWrapper>
  );
}

function OpenChoiceOtherInput({
  item,
  answers,
  onChange,
  options,
  disabled,
}: {
  item: QuestionnaireItem;
  answers: QuestionnaireResponseItemAnswer[];
  onChange: (
    linkId: string,
    answers: QuestionnaireResponseItemAnswer[],
  ) => void;
  options: QuestionnaireItemAnswerOption[];
  disabled?: boolean;
}) {
  const knownCodes = new Set(options.map(getOptionValue));
  const otherAnswer = answers.find((a) => {
    const code = a.valueCoding?.code ?? a.valueString ?? "";
    return code !== "" && !knownCodes.has(code);
  });
  const otherText = otherAnswer?.valueString ?? "";

  return (
    <div className="mt-2">
      <Input
        placeholder="Other..."
        value={otherText}
        onChange={(e) => {
          const known = answers.filter((a) => {
            const code = a.valueCoding?.code ?? a.valueString ?? "";
            return knownCodes.has(code);
          });
          const next = e.target.value
            ? [...known, { valueString: e.target.value }]
            : known;
          onChange(item.linkId, next);
        }}
        disabled={disabled}
      />
    </div>
  );
}

function UnsupportedItem({ item }: ItemProps) {
  return (
    <div className="text-xs text-muted-foreground p-2 bg-muted rounded">
      Unsupported item type: <code>{item.type}</code>
      {item.text && ` - ${item.text}`}
    </div>
  );
}

// --- Repeating item support ---

export function RepeatableItemField(props: ItemProps) {
  const { item, answers, onChange, allAnswers, readOnly } = props;

  if (!evaluateEnableWhen(item, allAnswers)) return null;

  // Groups and non-repeating items delegate directly
  if (
    !item.repeats ||
    item.type === "group" ||
    item.type === "choice" ||
    item.type === "open-choice"
  ) {
    return <QuestionnaireItemField {...props} />;
  }

  const handleAdd = () => {
    onChange(item.linkId, [...answers, {}]);
  };

  const handleRemove = (index: number) => {
    onChange(
      item.linkId,
      answers.filter((_, i) => i !== index),
    );
  };

  const handleChangeAt = (
    index: number,
    newAnswer: QuestionnaireResponseItemAnswer,
  ) => {
    const updated = answers.map((a, i) => (i === index ? newAnswer : a));
    onChange(item.linkId, updated);
  };

  // For repeating primitives, render each answer as a separate input
  const effectiveAnswers = answers.length > 0 ? answers : [{}];

  return (
    <div className="space-y-2">
      {item.text && (
        <Label className="text-sm">
          {item.text}
          {item.required && <span className="text-destructive ml-0.5">*</span>}
        </Label>
      )}
      {effectiveAnswers.map((answer, index) => (
        <div
          key={`${item.linkId}-${index}`}
          className="flex items-center gap-2"
        >
          <div className="flex-1">
            <RepeatingPrimitiveInput
              item={item}
              answer={answer}
              onChange={(a) => handleChangeAt(index, a)}
              readOnly={readOnly || item.readOnly}
            />
          </div>
          {effectiveAnswers.length > 1 && (
            <Button
              variant="ghost"
              size="sm"
              className="h-8 w-8 p-0"
              onClick={() => handleRemove(index)}
              disabled={readOnly || item.readOnly}
            >
              <Trash2 className="h-3 w-3" />
            </Button>
          )}
        </div>
      ))}
      <Button
        variant="outline"
        size="sm"
        onClick={handleAdd}
        disabled={readOnly || item.readOnly}
      >
        <Plus className="h-3 w-3 mr-1" />
        Add
      </Button>
    </div>
  );
}

function RepeatingPrimitiveInput({
  item,
  answer,
  onChange,
  readOnly,
}: {
  item: QuestionnaireItem;
  answer: QuestionnaireResponseItemAnswer;
  onChange: (answer: QuestionnaireResponseItemAnswer) => void;
  readOnly?: boolean;
}) {
  switch (item.type) {
    case "string":
      return (
        <Input
          value={answer.valueString ?? ""}
          onChange={(e) => onChange({ valueString: e.target.value })}
          disabled={readOnly}
        />
      );
    case "text":
      return (
        <Textarea
          value={answer.valueString ?? ""}
          onChange={(e) => onChange({ valueString: e.target.value })}
          disabled={readOnly}
        />
      );
    case "date":
      return (
        <Input
          type="date"
          value={answer.valueDate ?? ""}
          onChange={(e) => onChange({ valueDate: e.target.value })}
          disabled={readOnly}
          className="w-fit"
        />
      );
    case "integer":
      return (
        <NumberInput
          step="1"
          value={answer.valueInteger ?? ""}
          onChange={(e) => {
            const parsed = Number.parseInt(
              (e.target as HTMLInputElement).value,
              10,
            );
            onChange(Number.isNaN(parsed) ? {} : { valueInteger: parsed });
          }}
          disabled={readOnly}
          className="w-fit"
        />
      );
    case "decimal":
      return (
        <NumberInput
          step="any"
          value={answer.valueDecimal ?? ""}
          onChange={(e) => {
            const parsed = Number.parseFloat(
              (e.target as HTMLInputElement).value,
            );
            onChange(Number.isNaN(parsed) ? {} : { valueDecimal: parsed });
          }}
          disabled={readOnly}
          className="w-fit"
        />
      );
    default:
      return (
        <Input
          value={answer.valueString ?? ""}
          onChange={(e) => onChange({ valueString: e.target.value })}
          disabled={readOnly}
        />
      );
  }
}
