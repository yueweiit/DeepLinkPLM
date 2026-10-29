import { launchStage } from '@/constants/productResearch'
export const RESEARCH_BUSINESS = [
  {
    kind: "cost",
    path: "/product-research-costs",
    title: "项目成本",
    icon: "Coin",
    permission: "finance:read",
  },
  {
    kind: "promotion",
    path: "/product-research-promotions",
    title: "渠道推广",
    icon: "Promotion",
    permission: "project:view",
  },
  {
    kind: "presale",
    path: "/product-research-presales",
    title: "预售管理",
    icon: "ShoppingCart",
    permission: "project:view",
  },
] as const;
export type ResearchBusinessKind = (typeof RESEARCH_BUSINESS)[number]["kind"];
export function researchBusiness(kind: string) {
  return RESEARCH_BUSINESS.find((item) => item.kind === kind);
}
export function researchBusinessRoute(
  kind: string,
  productId?: number,
  stage?: number,
) {
  return {
    path: researchBusiness(kind)?.path || "/product-research",
    query: {
      ...(productId ? { productId: String(productId) } : {}),
      ...(stage ? { step: launchStage(stage).key } : {}),
    },
  };
}
export function researchSourceQuery(
  productId: number,
  workspace: string,
  stage: number,
) {
  return {
    researchProjectId: String(productId),
    researchWorkspace: workspace,
    researchStage: String(stage),
  };
}
