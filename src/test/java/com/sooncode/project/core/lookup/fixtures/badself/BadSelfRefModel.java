package com.sooncode.project.core.lookup.fixtures.badself;

import com.sooncode.project.core.annotations.Lookup;
import com.sooncode.project.core.annotations.LookupModel;
import com.sooncode.project.core.model.DomainModel;

/** 非法：fromModel 与本地模型相同（自环）。 */
@LookupModel
public class BadSelfRefModel extends DomainModel<BadSelfRefModel> {

    private String code;

    @Lookup(fromModel = BadSelfRefModel.class, localField = "code", fromField = "code")
    private String codeCopy;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getCodeCopy() { return codeCopy; }
    public void setCodeCopy(String codeCopy) { this.codeCopy = codeCopy; }
}
