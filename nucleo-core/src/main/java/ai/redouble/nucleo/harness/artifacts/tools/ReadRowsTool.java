/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.artifacts.tools;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.*;

import java.time.*;
import java.util.*;

/**
 * Tool for reading rows of a list the conversation holds.
 *
 * <p>A model is shown a list as a digest: how many rows it has and the first few. The other
 * rows are held by the registry, unseen. This tool returns a run of rows the model names by
 * the list's reference, the first row and a count, so it reads as many as it chooses with one
 * call and copies no row references. Nothing is fetched or searched: the rows are the ones
 * the list already holds.
 *
 * <p>Failures are typed refusals: a reference the registry does not resolve is
 * {@link ResourceNotFoundException}; a reference to something that is no list, a first row
 * or a count below 1, and a first row past the end of the list are
 * {@link InvalidInputException}, the last naming how many rows the list holds. A run that
 * starts inside the list and reaches past its end returns the rows there are. A missing
 * registry is the caller's wiring fault, {@link SystemException}.
 *
 * @author Andrey Santrosyan
 * @since 0.1.5 (2026-11-21)
 */
@DisplayName(value = "Read Rows", action = "Reading rows of a list")
@ToolName("read_rows")
@ToolDescription(value = "Read rows of a list in full. A list is shown with only its first few rows; use this to read any others, by the list's reference, the first row and how many.", readOnly = true)
@ToolWeight(type = ToolType.IN_MEMORY)
public class ReadRowsTool extends AbstractTool<ReadRowsInput, ReadRowsOutput> implements ArtifactRegistryAware {
    /** Rows returned when the model names no count. */
    public static final long DEFAULT_HOW_MANY = 10L;
    private ArtifactRegistry registry;

    public ReadRowsTool(Identifiable parent) {
        super(parent);
        setTimeout(Duration.ofSeconds(5));
    }

    @Override
    public void setArtifactRegistry(ArtifactRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ArtifactRegistry getArtifactRegistry() {
        return registry;
    }

    @Override
    public JobRequirements getRequirements() {
        JobRequirements req = new JobRequirements();
        req.setRequiresTransaction(false);
        return req;
    }

    @Override
    public ReadRowsOutput execute(JobResources resources, JobContext<ReadRowsOutput> context) throws LLMReadableCheckedException {
        if (registry == null) {
            throw new SystemException("ReadRowsTool",
                    "read_rows runs only as a tool of a thinker, which injects the conversation's artifact registry before submission", null);
        }
        Artifact artifact = registry.get(input.getList());
        if (artifact == null) {
            throw new ResourceNotFoundException("list", input.getList());
        }
        if (!(artifact instanceof ListArtifact<?> list)) {
            throw new InvalidInputException("list", input.getList(), "The reference of a list; this one is a single " + ArtifactRegistry.extractAliasFromRef(artifact.getArtifactRef()));
        }
        long fromRow = input.getFromRow() != null ? input.getFromRow() : 1L;
        long howMany = input.getHowMany() != null ? input.getHowMany() : DEFAULT_HOW_MANY;
        long rowsInList = list.getIterands().size();
        if (fromRow < 1) {
            throw new InvalidInputException("fromRow", input.getFromRow(), "Must be 1 or greater; rows are counted from 1");
        }
        if (howMany < 1) {
            throw new InvalidInputException("howMany", input.getHowMany(), "Must be 1 or greater");
        }
        if (fromRow > rowsInList) {
            throw new InvalidInputException("fromRow", fromRow, "A row of the list, which holds " + rowsInList + " rows");
        }
        // A run that reaches past the end returns the rows there are: reading "the next ten"
        // at the tail of a list is an ordinary request, and rowsInList tells the model it is done
        long end = Math.min(rowsInList, fromRow - 1 + howMany);
        ReadRowsOutput output = new ReadRowsOutput();
        output.setRowsInList(rowsInList);
        output.setFromRow(fromRow);
        output.setRows(new ArrayList<>(list.getIterands().subList((int) (fromRow - 1), (int) end)));
        return output;
    }
}
