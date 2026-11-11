import { describe, expect, it } from "vitest";
import { amountInput, integerInput, money } from "./models";

describe("declared facts", () => {
  it("keeps unknown distinct from an explicit zero", () => {
    expect(amountInput("")).toBeNull();
    expect(amountInput("0")).toBe(0);
    expect(money(null)).toBe("Unknown");
    expect(money(0)).not.toBe("Unknown");
  });
  it("accepts only bounded decimal EUR amounts", () => {
    expect(amountInput("120.50")).toBe(120.5);
    for (const value of ["-1", "1.234", "NaN", "1e3", "1234567890123"])
      expect(() => amountInput(value)).toThrow();
  });
  it("validates whole-number ranges without converting missing facts to zero", () => {
    expect(integerInput("", 0, 20)).toBeNull();
    expect(integerInput("0", 0, 20)).toBe(0);
    for (const value of ["21", "-1", "2.5", "unknown"])
      expect(() => integerInput(value, 0, 20)).toThrow();
  });
});
