package uz.hamkorbank.commhub.application.port.out;

import java.util.List;
import java.util.Optional;

/** Operator-editable system parameters (§11.2 "Администрирование", NF-06, NF-07). */
public interface SystemParameterPort {

    /** Every parameter, by key; the administration screen shows all of them at once. */
    List<SystemParameter> findAll();

    Optional<SystemParameter> find(String key);

    /** Writes the parameter, creating it when the key is new. */
    SystemParameter save(SystemParameter parameter);

    /** Removes a parameter; the code that reads it falls back to its deployed default (NF-06). */
    void delete(String key);
}
