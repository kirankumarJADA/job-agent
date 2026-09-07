import { describe, it, expect } from 'vitest';
import { ScoringWeights } from './types';

describe('ScoringWeights Validation Logic', () => {
  it('should validate standard default weights sum to exactly 100', () => {
    const defaultWeights: ScoringWeights = {
      skill: 30,
      experience: 15,
      visa: 20,
      location: 10,
      salary: 10,
      career: 10,
      difficulty: 5,
    };

    const sum = Object.values(defaultWeights).reduce((a, b) => a + b, 0);
    expect(sum).toBe(100);
  });

  it('should detect when weights do not sum to 100', () => {
    const invalidWeights: ScoringWeights = {
      skill: 30,
      experience: 15,
      visa: 20,
      location: 10,
      salary: 10,
      career: 10,
      difficulty: 4, // 99 total
    };

    const sum = Object.values(invalidWeights).reduce((a, b) => a + b, 0);
    expect(sum).not.toBe(100);
    expect(sum).toBe(99);
  });
});
