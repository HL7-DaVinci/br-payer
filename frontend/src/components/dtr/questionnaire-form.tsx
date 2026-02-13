import type {
  Questionnaire,
  QuestionnaireItem,
  QuestionnaireResponse,
  QuestionnaireResponseItem,
  QuestionnaireResponseItemAnswer,
} from "fhir/r4";
import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useState,
} from "react";
import { RepeatableItemField } from "./questionnaire-item";

export interface QuestionnaireFormHandle {
  getQuestionnaireResponse(): QuestionnaireResponse | null;
}

interface QuestionnaireFormProps {
  questionnaire: Questionnaire;
  questionnaireResponse?: QuestionnaireResponse | null;
  readOnly?: boolean;
}

// Recursively extract answers from a QuestionnaireResponse into a flat map
function extractAnswersFromQR(
  qr: QuestionnaireResponse | null | undefined,
): Map<string, QuestionnaireResponseItemAnswer[]> {
  const map = new Map<string, QuestionnaireResponseItemAnswer[]>();
  if (!qr?.item) return map;

  function walk(items: QuestionnaireResponseItem[]) {
    for (const item of items) {
      if (item.answer?.length) {
        map.set(item.linkId, item.answer);
        // Nested items inside answers (group-within-answer pattern)
        for (const answer of item.answer) {
          if (answer.item?.length) walk(answer.item);
        }
      }
      if (item.item?.length) walk(item.item);
    }
  }

  walk(qr.item);
  return map;
}

// Build QuestionnaireResponse from current answers, guided by the Questionnaire structure
function buildQR(
  questionnaire: Questionnaire,
  answers: Map<string, QuestionnaireResponseItemAnswer[]>,
  originalQr?: QuestionnaireResponse | null,
): QuestionnaireResponse {
  function buildItems(
    qItems: QuestionnaireItem[] | undefined,
  ): QuestionnaireResponseItem[] {
    if (!qItems) return [];

    return qItems
      .map((qItem): QuestionnaireResponseItem | null => {
        const itemAnswers = answers.get(qItem.linkId) ?? [];
        const childItems = qItem.item ? buildItems(qItem.item) : [];

        if (qItem.type === "group") {
          if (childItems.length === 0) return null;
          return { linkId: qItem.linkId, text: qItem.text, item: childItems };
        }

        // Only include items that have answers
        if (itemAnswers.length === 0 && childItems.length === 0) return null;

        const responseItem: QuestionnaireResponseItem = {
          linkId: qItem.linkId,
          text: qItem.text,
        };

        if (itemAnswers.length > 0) {
          // Filter out empty answer objects
          const validAnswers = itemAnswers.filter(
            (a) => Object.keys(a).length > 0,
          );
          if (validAnswers.length > 0) {
            responseItem.answer = validAnswers;
          }
        }

        if (childItems.length > 0) {
          responseItem.item = childItems;
        }

        return responseItem;
      })
      .filter((item): item is QuestionnaireResponseItem => item !== null);
  }

  const qr: QuestionnaireResponse = {
    resourceType: "QuestionnaireResponse",
    status: originalQr?.status ?? "in-progress",
    questionnaire:
      originalQr?.questionnaire ??
      questionnaire.url ??
      `Questionnaire/${questionnaire.id}`,
    item: buildItems(questionnaire.item),
  };

  // Preserve metadata from original QR
  if (originalQr?.id) qr.id = originalQr.id;
  if (originalQr?.meta) qr.meta = originalQr.meta;
  if (originalQr?.contained) qr.contained = originalQr.contained;
  if (originalQr?.extension) qr.extension = originalQr.extension;
  if (originalQr?.authored) qr.authored = originalQr.authored;
  if (originalQr?.subject) qr.subject = originalQr.subject;

  return qr;
}

export const QuestionnaireForm = forwardRef<
  QuestionnaireFormHandle,
  QuestionnaireFormProps
>(function QuestionnaireForm(
  { questionnaire, questionnaireResponse, readOnly },
  ref,
) {
  const [answers, setAnswers] = useState<
    Map<string, QuestionnaireResponseItemAnswer[]>
  >(() => extractAnswersFromQR(questionnaireResponse));

  const handleChange = useCallback(
    (linkId: string, newAnswers: QuestionnaireResponseItemAnswer[]) => {
      setAnswers((prev) => {
        const next = new Map(prev);
        if (newAnswers.length > 0) {
          next.set(linkId, newAnswers);
        } else {
          next.delete(linkId);
        }
        return next;
      });
    },
    [],
  );

  useImperativeHandle(
    ref,
    () => ({
      getQuestionnaireResponse: () =>
        buildQR(questionnaire, answers, questionnaireResponse),
    }),
    [questionnaire, answers, questionnaireResponse],
  );

  // Re-initialize when questionnaireResponse identity changes (adaptive flow)
  useEffect(() => {
    setAnswers(extractAnswersFromQR(questionnaireResponse));
  }, [questionnaireResponse]);

  return (
    <div className="space-y-4">
      {questionnaire.item?.map((item) => (
        <RepeatableItemField
          key={item.linkId}
          item={item}
          answers={answers.get(item.linkId) ?? []}
          onChange={handleChange}
          allAnswers={answers}
          readOnly={readOnly}
        />
      ))}
    </div>
  );
});
