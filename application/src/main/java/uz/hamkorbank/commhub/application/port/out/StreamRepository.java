package uz.hamkorbank.commhub.application.port.out;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** Registry of the source systems (§10.1 {@code stream}, FR-1.3, §18.4). */
public interface StreamRepository {

    Stream save(Stream stream);

    Optional<Stream> findById(StreamId streamId);

    List<Stream> findAll();
}
