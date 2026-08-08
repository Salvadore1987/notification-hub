package uz.hamkorbank.commhub.application.mapper;

import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.application.dto.SystemParameterView;
import uz.hamkorbank.commhub.application.port.out.SystemParameter;

/** System parameter → its read model (§11.2 "Администрирование"). */
@Mapper(componentModel = "spring")
public interface SystemParameterMapper {

    SystemParameterView toView(SystemParameter parameter);
}
