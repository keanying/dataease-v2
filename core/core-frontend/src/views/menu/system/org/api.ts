import request from "@/config/axios";

export const sysOrgMemberPageApi = (data: any) => {
  return request.post({ url: "/sys/org/member/page", data });
};

// orgId: target org, candidates exclude its existing members (users may belong to several orgs)
export const sysOrgMemberCandidatesApi = (data: any, orgId?: string) => {
  return request.post({
    url: "/sys/org/member/candidates",
    params: orgId ? { orgId } : undefined,
    data,
  });
};

export const sysOrgMemberRoleOptionsApi = (orgId: string) => {
  return request.post({ url: "/sys/org/member/roleOptions", data: { orgId } });
};

export const sysOrgMemberAddApi = (data: any) => {
  return request.post({ url: "/sys/org/member/add", data });
};

export const sysOrgMemberRemoveApi = (userId: string, orgId: string) => {
  return request.post({ url: `/sys/org/member/remove/${userId}/${orgId}` });
};

export const sysOrgMemberSwitchRoleApi = (data: any) => {
  return request.post({ url: "/sys/org/member/switchRole", data });
};

export const lazyTreeApi = (data: any) => request.post({ url: "/org/page/lazyTree", data });

// Reuse existing org APIs for CRUD
export { saveApi as createApi, updateApi as editApi, deleteApi } from "@/api/org";
