package uz.hamkorbank.commhub.application.port.in;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.SystemParameterView;
import uz.hamkorbank.commhub.application.port.in.command.SetSystemParameterCommand;

/**
 * Operator-editable system parameters (§11.2 "Администрирование", NF-06).
 *
 * <p>Grouped into one interface like the other {@code Manage…} use cases: the operations share their
 * transaction and their audit entry. Every write is journalled with its before and after — a parameter
 * is a lever on running traffic, and "who set this to zero" has to be answerable (FR-7.3).
 */
public interface ManageSystemParameters {

    List<SystemParameterView> list();

    SystemParameterView set(SetSystemParameterCommand command);

    /** Removes the parameter; the code reading it falls back to its deployed default (NF-06). */
    void remove(SetSystemParameterCommand command);
}
