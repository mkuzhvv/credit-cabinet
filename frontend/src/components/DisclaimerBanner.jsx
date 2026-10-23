import { ShieldAlert } from 'lucide-react';

export default function DisclaimerBanner() {
  return (
    <div className="mb-6 bg-amber-50/90 border border-amber-200/80 rounded-2xl p-4 flex items-start sm:items-center gap-3 text-amber-900">
      <ShieldAlert className="w-5 h-5 text-amber-600 shrink-0 mt-0.5 sm:mt-0" />
      <p className="text-xs sm:text-sm leading-relaxed">
        <span className="font-semibold">Учебный проект. Условия демонстрационные.</span>{' '}
        Все суммы, ставки, сроки и требования предложены для прототипа и не являются тарифами реального банка.
      </p>
    </div>
  );
}