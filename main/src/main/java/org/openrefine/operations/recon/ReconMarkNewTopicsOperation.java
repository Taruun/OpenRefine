/*

Copyright 2010, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,           
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY           
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package org.openrefine.operations.recon;

import java.util.Map.Entry;

import org.openrefine.browsing.EngineConfig;
import org.openrefine.browsing.facets.RowAggregator;
import org.openrefine.model.Cell;
import org.openrefine.model.GridState;
import org.openrefine.model.Row;
import org.openrefine.model.RowFilter;
import org.openrefine.model.RowMapper;
import org.openrefine.model.changes.Change.DoesNotApplyException;
import org.openrefine.model.changes.ChangeContext;
import org.openrefine.model.changes.ColumnNotFoundException;
import org.openrefine.model.recon.LazyReconStats;
import org.openrefine.model.recon.Recon;
import org.openrefine.model.recon.Recon.Judgment;
import org.openrefine.model.recon.ReconConfig;
import org.openrefine.operations.ImmediateRowMapOperation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

/**
 * Marks all filtered cells in a given column as reconciled to "new".
 * Similar values can either be matched to the same reconciliation id,
 * or distinct ones.
 */
public class ReconMarkNewTopicsOperation extends ImmediateRowMapOperation {
	
    final protected boolean    _shareNewTopics;
    final protected String     _columnName;
    
    @JsonCreator
    public ReconMarkNewTopicsOperation(
            @JsonProperty("engineConfig")
            EngineConfig engineConfig,
            @JsonProperty("columnName")
            String columnName,
            @JsonProperty("shareNewTopics")
            boolean shareNewTopics) {
        super(engineConfig);
        _columnName = columnName;
        _shareNewTopics = shareNewTopics;
    }
    
    @JsonProperty("columnName")
    public String getColumnName() {
        return _columnName;
    }
    
    @JsonProperty("shareNewTopics")
    public boolean getShareNewTopics() {
        return _shareNewTopics;
    }
    
    @Override
	public String getDescription() {
        return "Mark to create new items for cells in column " + _columnName +
            (_shareNewTopics ? 
                ", one item for each group of similar cells" : 
                ", one item for each cell");
    }
    
    @Override
    public RowMapper getPositiveRowMapper(GridState state, ChangeContext context) throws DoesNotApplyException {
    	int columnIndex = state.getColumnModel().getColumnIndexByName(_columnName);
    	if (columnIndex == -1) {
    		throw new ColumnNotFoundException(_columnName);
    	}
    	ReconConfig reconConfig = state.getColumnModel().getColumnByName(_columnName).getReconConfig();
    	if (reconConfig == null) {
    		// TODO let the user supply its own recon config via the UI (just like UseValuesAsIdentifiers)
    		throw new DoesNotApplyException(String.format("Column '%s' is not reconciled", _columnName));
    	}
    	long historyEntryId = context.getHistoryEntryId();
    	
    	if (_shareNewTopics) {
    		// Aggregate the set of distinct values
    		ImmutableMap<String, Long> empty = ImmutableMap.of();
    		RowFilter filter = createEngine(state).combinedRowFilters();
    		ImmutableMap<String, Long> valueToId = state.aggregateRows(aggregator(columnIndex, filter), empty);
    		
    		return rowMapperWithSharing(columnIndex, reconConfig, historyEntryId, valueToId);
    	} else {
    		return rowMapperNoSharing(columnIndex, reconConfig, historyEntryId);
    	}
    }
    
    protected static RowMapper rowMapperWithSharing(int columnIndex, ReconConfig reconConfig, long historyEntryId, ImmutableMap<String, Long> valueToId) {
		return new RowMapper() {

			private static final long serialVersionUID = -2838679493823196821L;

			@Override
			public Row call(long rowId, Row row) {
                Cell cell = row.getCell(columnIndex);
                if (cell != null) {
                    Recon recon = reconConfig.createNewRecon(historyEntryId)
                    		.withJudgment(Judgment.New)
                    		.withJudgmentAction("mass");
                    String s = cell.value == null ? "" : cell.value.toString();
                    if (valueToId.containsKey(s)) {
                    	recon = recon.withId(valueToId.get(s));
                    }
                    
                    Cell newCell = new Cell(cell.value, recon);
                    
                    return row.withCell(columnIndex, newCell);
                }
				return row;
			}
			
		};
    }
    
    @Override
	protected GridState postTransform(GridState newState, ChangeContext context) {
		return LazyReconStats.updateReconStats(newState, _columnName);
	}
    
    protected static RowMapper rowMapperNoSharing(int columnIndex, ReconConfig reconConfig, long historyEntryId) {
		return new RowMapper() {

			private static final long serialVersionUID = 5224856110246957223L;

			@Override
			public Row call(long rowId, Row row) {
                Cell cell = row.getCell(columnIndex);
                if (cell != null) {
                    Recon recon = cell.recon == null ? reconConfig.createNewRecon(historyEntryId) : cell.recon.dup(historyEntryId);
                    recon = recon
                    		.withMatch(null)
                    		.withMatchRank(-1)
                    		.withJudgment(Judgment.New)
                    		.withJudgmentAction("mass");

                    Cell newCell = new Cell(cell.value, recon);
                    
                    return row.withCell(columnIndex, newCell);
                }
				return row;
			}
			
		};
    }
    
    protected static RowAggregator<ImmutableMap<String, Long>> aggregator(int columnIndex, RowFilter filter) {
    	return new RowAggregator<ImmutableMap<String, Long>>() {

			private static final long serialVersionUID = 2749743046303701107L;

			@Override
			public ImmutableMap<String, Long> sum(ImmutableMap<String, Long> first, ImmutableMap<String, Long> second) {
				Builder<String, Long> builder = ImmutableMap.<String,Long>builder().putAll(first);
				// sadly we cannot call `putAll(second)` as conflicting keys will raise an exception
				for(Entry<String, Long> entry : second.entrySet()) {
					if (!first.containsKey(entry.getKey())) {
						builder.put(entry.getKey(), entry.getValue());
					}
				}
				return builder.build();
			}

			@Override
			public ImmutableMap<String, Long> withRow(ImmutableMap<String, Long> state, long rowId, Row row) {
				if (!filter.filterRow(rowId, row)) {
					return state;
				}
				Cell cell = row.getCell(columnIndex);
				if (cell != null && cell.value != null) {
					String value = cell.value.toString();
					if (!state.containsKey(value)) {
						long reconId = new Recon(0L, "", "").id;
						return ImmutableMap.<String,Long>builder().putAll(state).put(value, reconId).build();
					}
				}
				return state;
			}
    		
    	};
    }
}