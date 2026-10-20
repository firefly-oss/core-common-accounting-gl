/*
 * Copyright 2025 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.fireflysoftware.core.accounting.core.services.journal.v1;

import org.fireflyframework.core.filters.FilterRequest;
import org.fireflyframework.core.filters.FilterUtils;
import org.fireflyframework.core.queries.PaginationResponse;
import io.fireflysoftware.core.accounting.core.mappers.journal.v1.JournalLineMapper;
import io.fireflysoftware.core.accounting.intefaces.dtos.journal.v1.JournalLineDTO;
import io.fireflysoftware.core.accounting.models.entities.journal.v1.JournalLine;
import io.fireflysoftware.core.accounting.models.repositories.journal.v1.JournalLineRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Service
@Transactional
public class JournalLineServiceImpl implements JournalLineService {

    @Autowired
    private JournalLineRepository repository;

    @Autowired
    private JournalLineMapper mapper;

    @Override
    public Mono<JournalLineDTO> create(UUID batchId, UUID entryId, JournalLineDTO dto) {
        return Mono.just(dto)
                .map(inputDto -> {
                    inputDto.setEntryId(entryId);
                    return mapper.toEntity(inputDto);
                })
                .flatMap(entity -> repository.save(entity))
                .map(savedEntity -> mapper.toDTO(savedEntity));
    }

    @Override
    public Mono<JournalLineDTO> getById(UUID batchId, UUID entryId, UUID lineId) {
        return repository.findById(lineId)
                .filter(entity -> entity.getEntryId().equals(entryId))
                .map(mapper::toDTO)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("No journal line found with ID: " + lineId)));
    }

    @Override
    public Mono<JournalLineDTO> update(UUID batchId, UUID entryId, UUID lineId, JournalLineDTO dto) {
        return repository.findById(lineId)
                .filter(entity -> entity.getEntryId().equals(entryId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("No journal line found with ID: " + lineId)))
                .flatMap(existingEntity -> {
                    existingEntity.setAccountId(dto.getAccountId());
                    existingEntity.setAmount(dto.getAmount());
                    existingEntity.setDrCr(dto.getDrCr());
                    existingEntity.setMemo(dto.getMemo());
                    existingEntity.setFxCurrency(dto.getFxCurrency());
                    existingEntity.setFxRate(dto.getFxRate());
                    existingEntity.setUpdatedAt(dto.getUpdatedAt());
                    return repository.save(existingEntity);
                })
                .map(mapper::toDTO);
    }

    @Override
    public Mono<Void> delete(UUID batchId, UUID entryId, UUID lineId) {
        return repository.findById(lineId)
                .filter(entity -> entity.getEntryId().equals(entryId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("No journal line found with ID: " + lineId)))
                .flatMap(repository::delete);
    }

    @Override
    public Mono<PaginationResponse<JournalLineDTO>> search(UUID batchId, UUID entryId, FilterRequest<JournalLineDTO> filterRequest) {
        filterRequest.getFilters().setEntryId(entryId);
        return FilterUtils.createFilter(
                JournalLine.class,
                mapper::toDTO
        ).filter(filterRequest);
    }
}
