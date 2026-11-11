export const employmentValues = [
  "UNKNOWN",
  "EMPLOYED",
  "SELF_EMPLOYED",
  "RETIRED",
  "OTHER",
] as const;
export type Employment = (typeof employmentValues)[number];
export type Status = "DRAFT" | "IN_REVIEW" | "CLOSED";
export type Profile = {
  customerAlias: string;
  age: number | null;
  employment: Employment;
  dependants: number | null;
  monthlyIncome: number | null;
  pensionContribution: number | null;
  lifeCover: number | null;
  notes: string | null;
};
export type CaseView = {
  id: number;
  revision: number;
  status: Status;
  profile: Profile;
  updatedAt: string;
};
export type Change = {
  revision: number;
  actor: string;
  action: string;
  changedAt: string;
  snapshot: CaseView;
};
export type CaseDetail = { caseData: CaseView; history: Change[] };

export const emptyProfile: Profile = {
  customerAlias: "",
  age: null,
  employment: "UNKNOWN",
  dependants: null,
  monthlyIncome: null,
  pensionContribution: null,
  lifeCover: null,
  notes: null,
};

export function amountInput(value: string): number | null {
  if (value.trim() === "") return null;
  if (!/^\d{1,12}(\.\d{1,2})?$/.test(value)) {
    throw new Error(
      "Amounts must be non-negative, with at most 12 digits and two decimal places.",
    );
  }
  return Number(value);
}

export function integerInput(
  value: string,
  minimum: number,
  maximum: number,
): number | null {
  if (value.trim() === "") return null;
  const result = Number(value);
  if (
    !/^\d+$/.test(value) ||
    !Number.isInteger(result) ||
    result < minimum ||
    result > maximum
  ) {
    throw new Error(
      `Enter a whole number between ${minimum} and ${maximum}, or leave it blank.`,
    );
  }
  return result;
}

export function money(value: number | null): string {
  return value === null
    ? "Unknown"
    : new Intl.NumberFormat("en-FI", {
        style: "currency",
        currency: "EUR",
      }).format(value);
}

export function label(value: string): string {
  return value
    .toLowerCase()
    .replaceAll("_", " ")
    .replace(/^./, (first) => first.toUpperCase());
}
