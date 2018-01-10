package org.openrefine.wikidata.schema;


import org.openrefine.wikidata.schema.entityvalues.NewEntityIdValue;
import org.wikidata.wdtk.datamodel.helpers.Datamodel;
import org.wikidata.wdtk.datamodel.interfaces.ItemIdValue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.google.refine.model.Cell;
import com.google.refine.model.Recon;
import com.google.refine.model.ReconCandidate;

public class WbItemVariable extends WbItemExpr {
    /* An item that depends on a reconciled value in a column */
    
    private String columnName;
    
    @JsonCreator
    public WbItemVariable(
            @JsonProperty("columnName") String columnName) {
        this.columnName = columnName;
    }

    @Override
    public ItemIdValue evaluate(ExpressionContext ctxt) throws SkipStatementException {
        Cell cell = ctxt.getCellByName(getColumnName());
        if (cell != null && cell.recon != null) {
            Recon recon = cell.recon;
            if (recon.judgment == Recon.Judgment.Matched && cell.recon.match != null) {
                ReconCandidate match = cell.recon.match;
                return Datamodel.makeItemIdValue(match.id, ctxt.getBaseIRI());
            } else if (recon.judgment == Recon.Judgment.New) {
                int rowId = ctxt.getRowId();
                int columnId = ctxt.getCellIndexByName(getColumnName());
                String siteIRI = ctxt.getBaseIRI();
                String label = ctxt.getCellByName(getColumnName()).value.toString();
                return new NewEntityIdValue(
                        rowId, columnId, siteIRI, label);
            }
        }
        throw new SkipStatementException();
    }

    public String getColumnName() {
        return columnName;
    }
}
