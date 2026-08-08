package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.DashboardView;
import uz.hamkorbank.commhub.application.port.in.query.DashboardQuery;

/** The summary screen of the admin panel (§11.2 "Дашборд", UI-03). */
public interface GetDashboard {

    DashboardView summary(DashboardQuery query);
}
