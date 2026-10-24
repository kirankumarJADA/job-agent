/**
 * Phase 6: pure planning for a LEVER_APPLY step.
 *
 * <p>Unlike Zoho Recruit (worker/src/zoho_recruit.js), the backend has
 * already inspected the real, server-rendered Lever form and classified
 * every field (LeverFieldMapper, LeverExecutionPlanService) — this module
 * does not classify anything live. It only turns that backend-computed field
 * list into a bounded sequence of concrete browser actions, and separately
 * reports what still needs the candidate. Nothing here invents a value: a
 * field with no backend-supplied value is never filled, regardless of its
 * classification.
 */

export const LeverFieldAction = Object.freeze({
  FILL: 'FILL',
  SELECT: 'SELECT',
  CHECK: 'CHECK',
  UPLOAD: 'UPLOAD',
});

/**
 * @param {Array<{key:string,label:string,htmlType:string,required:boolean,
 *   selector:string,options:string[],classification:string,value:string}>} fields
 * @returns {{toFill: Array<object>, requiredGaps: Array<object>}}
 */
export function planLeverFormActions(fields) {
  const toFill = [];
  const requiredGaps = [];
  for (const field of fields ?? []) {
    const classification = field.classification;
    const supported = classification === 'SUPPORTED_AUTO' || classification === 'SUPPORTED_WITH_REVIEW';
    if (field.key === 'resume') {
      // Materialized as a separate UPLOAD_FILE-style action by the caller
      // (browser_worker.js#runLeverApply), using plan.package.cv — never a
      // text value for the file input itself.
      continue;
    }
    if (!supported || !field.value) {
      if (field.required) {
        requiredGaps.push({ key: field.key, label: field.label, classification, reason: field.reason || null });
      }
      continue;
    }
    if (['text', 'email', 'tel', 'textarea'].includes(field.htmlType)) {
      toFill.push({ action: LeverFieldAction.FILL, key: field.key, label: field.label, selector: field.selector, value: field.value });
    } else if (field.htmlType === 'select' && (field.options ?? []).includes(field.value)) {
      toFill.push({ action: LeverFieldAction.SELECT, key: field.key, label: field.label, selector: field.selector, value: field.value, options: field.options });
    } else if ((field.htmlType === 'radio' || field.htmlType === 'checkbox') && (field.options ?? []).includes(field.value)) {
      toFill.push({
        action: LeverFieldAction.CHECK, key: field.key, label: field.label,
        selector: `${field.selector}[value="${field.value}"]`, value: field.value, options: field.options,
      });
    } else if (field.required) {
      // A value exists but cannot be safely placed into this control type
      // (e.g. a select/radio whose value is not one of the form's own
      // options) — reported as a gap, never forced in anyway.
      requiredGaps.push({ key: field.key, label: field.label, classification, reason: 'value does not match an option this form actually offers' });
    }
  }
  return { toFill, requiredGaps };
}
