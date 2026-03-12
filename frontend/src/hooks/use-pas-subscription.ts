import { useCallback, useEffect, useRef, useState } from "react";
import type { SubscriptionStatus, TimelineEntry } from "@/lib/pas-types";
import { parseNotificationBundle } from "@/lib/pas-types";

interface UsePasSubscriptionOptions {
  serverUrl: string;
  onNotification: (entry: TimelineEntry) => void;
}

interface UsePasSubscriptionResult {
  status: SubscriptionStatus;
  npi: string;
  setNpi: (npi: string) => void;
  connect: () => void;
  disconnect: () => void;
  error: string | null;
  subscriptionId: string | null;
}

interface SubscriptionHandle {
  serverUrl: string;
  subscriptionId: string;
}

function buildSubscriptionResource(npi: string): object {
  return {
    resourceType: "Subscription",
    meta: {
      profile: [
        "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-subscription",
        "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-subscription",
      ],
    },
    status: "requested",
    reason: "Monitor PAS authorization notifications",
    criteria:
      "http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic",
    _criteria: {
      extension: [
        {
          url: "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-filter-criteria",
          valueString: `Bundle?orgIdentifier=${npi}`,
        },
      ],
    },
    channel: {
      type: "websocket",
      payload: "application/fhir+json",
      _payload: {
        extension: [
          {
            url: "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-payload-content",
            valueCode: "full-resource",
          },
        ],
      },
    },
  };
}

function deriveWsUrl(serverUrl: string): string {
  const base = new URL(serverUrl);
  base.protocol = base.protocol === "https:" ? "wss:" : "ws:";
  base.search = "";
  base.hash = "";

  const trimmedPath = base.pathname.replace(/\/+$/, "");
  const pathSegments = trimmedPath.split("/").filter(Boolean);

  if (pathSegments.length === 0) {
    base.pathname = "/websocket";
  } else {
    pathSegments[pathSegments.length - 1] = "websocket";
    base.pathname = `/${pathSegments.join("/")}`;
  }

  return base.toString().replace(/\/$/, "");
}

const ACTIVATION_POLL_MS = 500;
const ACTIVATION_TIMEOUT_MS = 15_000;
const CANCELLED_CONNECTION_ERROR = "Cancelled PAS subscription connection";

function createCancelledConnectionError(): Error {
  const error = new Error(CANCELLED_CONNECTION_ERROR);
  error.name = "PasSubscriptionCancelledError";
  return error;
}

function isCancelledConnectionError(error: unknown): boolean {
  return (
    error instanceof Error &&
    error.name === "PasSubscriptionCancelledError" &&
    error.message === CANCELLED_CONNECTION_ERROR
  );
}

function pause(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForSubscriptionActive(
  serverUrl: string,
  subscriptionId: string,
  isCurrentAttempt: () => boolean,
): Promise<void> {
  const deadline = Date.now() + ACTIVATION_TIMEOUT_MS;

  while (Date.now() < deadline) {
    if (!isCurrentAttempt()) {
      throw createCancelledConnectionError();
    }

    const res = await fetch(`${serverUrl}/Subscription/${subscriptionId}`, {
      headers: { Accept: "application/fhir+json" },
    });

    if (!isCurrentAttempt()) {
      throw createCancelledConnectionError();
    }

    if (res.ok) {
      const sub = await res.json();
      if (!isCurrentAttempt()) {
        throw createCancelledConnectionError();
      }

      if (sub.status === "active") return;
    }

    await pause(ACTIVATION_POLL_MS);
  }

  if (!isCurrentAttempt()) {
    throw createCancelledConnectionError();
  }

  throw new Error(
    "Subscription was not activated by the server within the timeout period",
  );
}

export function usePasSubscription({
  serverUrl,
  onNotification,
}: UsePasSubscriptionOptions): UsePasSubscriptionResult {
  const [status, setStatus] = useState<SubscriptionStatus>("idle");
  const [npi, setNpi] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [subscriptionId, setSubscriptionId] = useState<string | null>(null);

  // Stable refs to avoid stale closures
  const onNotificationRef = useRef(onNotification);
  onNotificationRef.current = onNotification;

  const wsRef = useRef<WebSocket | null>(null);
  const connectionAttemptRef = useRef(0);
  const activeSubscriptionRef = useRef<SubscriptionHandle | null>(null);

  const deleteSubscription = useCallback(
    (handle: SubscriptionHandle | null) => {
      if (!handle) {
        return;
      }

      fetch(`${handle.serverUrl}/Subscription/${handle.subscriptionId}`, {
        method: "DELETE",
      }).catch(() => {});
    },
    [],
  );

  const closeSocket = useCallback(() => {
    const ws = wsRef.current;
    if (!ws) {
      return;
    }

    ws.onopen = null;
    ws.onmessage = null;
    ws.onerror = null;
    ws.onclose = null;
    ws.close();
    wsRef.current = null;
  }, []);

  const clearTrackedSubscription = useCallback(() => {
    const handle = activeSubscriptionRef.current;
    activeSubscriptionRef.current = null;
    setSubscriptionId(null);
    return handle;
  }, []);

  const cleanup = useCallback(() => {
    connectionAttemptRef.current += 1;
    closeSocket();
    deleteSubscription(clearTrackedSubscription());
    setError(null);
    setStatus("idle");
  }, [clearTrackedSubscription, closeSocket, deleteSubscription]);

  const disconnect = useCallback(() => {
    cleanup();
  }, [cleanup]);

  const connect = useCallback(async () => {
    if (!npi.trim()) {
      setError("NPI is required");
      setStatus("error");
      return;
    }

    // Clean up any existing connection
    cleanup();

    setError(null);
    setStatus("creating");

    const attemptId = connectionAttemptRef.current;
    const connectServerUrl = serverUrl;
    const isCurrentAttempt = () => connectionAttemptRef.current === attemptId;

    try {
      // 1. Create Subscription resource via POST
      const subscriptionResource = buildSubscriptionResource(npi.trim());
      const response = await fetch(`${connectServerUrl}/Subscription`, {
        method: "POST",
        headers: { "Content-Type": "application/fhir+json" },
        body: JSON.stringify(subscriptionResource),
      });

      if (!response.ok) {
        const body = await response.text();
        throw new Error(
          `Failed to create Subscription: ${response.status} ${body}`,
        );
      }

      const created = await response.json();
      const subId = created.id;
      if (!subId) {
        throw new Error("Subscription created but no ID returned");
      }

      if (!isCurrentAttempt()) {
        deleteSubscription({
          serverUrl: connectServerUrl,
          subscriptionId: subId,
        });
        return;
      }

      activeSubscriptionRef.current = {
        serverUrl: connectServerUrl,
        subscriptionId: subId,
      };
      setSubscriptionId(subId);
      setStatus("connecting");

      // 2. Wait for HAPI to activate the subscription (async processing pipeline)
      await waitForSubscriptionActive(
        connectServerUrl,
        subId,
        isCurrentAttempt,
      );

      if (!isCurrentAttempt()) {
        return;
      }

      // 3. Open WebSocket
      const wsUrl = deriveWsUrl(connectServerUrl);
      const ws = new WebSocket(wsUrl);
      wsRef.current = ws;

      ws.onopen = () => {
        if (!isCurrentAttempt() || wsRef.current !== ws) {
          return;
        }

        setStatus("binding");
        ws.send(`bind ${subId}`);
      };

      ws.onmessage = (event) => {
        if (!isCurrentAttempt() || wsRef.current !== ws) {
          return;
        }

        const data = String(event.data);

        if (data.startsWith("bound ")) {
          setStatus("active");
          return;
        }

        if (data.startsWith("ping ")) {
          return;
        }

        // For topic subscriptions with full-resource payload, HAPI sends
        // the raw FHIR resource JSON directly as the WebSocket message
        try {
          const entry = parseNotificationBundle(JSON.parse(data));
          if (entry) {
            onNotificationRef.current(entry);
          }
        } catch {
          // Malformed JSON from WebSocket
        }
      };

      ws.onerror = () => {
        if (!isCurrentAttempt() || wsRef.current !== ws) {
          return;
        }

        setError("WebSocket connection error");
        setStatus("error");
      };

      ws.onclose = (event) => {
        if (wsRef.current === ws) {
          wsRef.current = null;
        }

        if (!isCurrentAttempt()) {
          return;
        }

        wsRef.current = null;
        // Only transition to disconnected if we were previously active
        setStatus((prev) => {
          if (prev === "active" || prev === "binding") {
            return "disconnected";
          }
          // If already in error/idle state, don't override
          return prev === "error" ? prev : "disconnected";
        });
        if (!event.wasClean) {
          setError(`WebSocket closed unexpectedly (code ${event.code})`);
        }
      };
    } catch (err) {
      if (!isCurrentAttempt()) {
        return;
      }

      closeSocket();
      deleteSubscription(clearTrackedSubscription());

      if (isCancelledConnectionError(err)) {
        return;
      }

      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      setStatus("error");
    }
  }, [
    clearTrackedSubscription,
    cleanup,
    closeSocket,
    deleteSubscription,
    npi,
    serverUrl,
  ]);

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      connectionAttemptRef.current += 1;
      closeSocket();
      deleteSubscription(clearTrackedSubscription());
    };
  }, [clearTrackedSubscription, closeSocket, deleteSubscription]);

  return {
    status,
    npi,
    setNpi,
    connect,
    disconnect,
    error,
    subscriptionId,
  };
}
