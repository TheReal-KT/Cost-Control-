package com.example.agentcostcontrol.ui;

import android.app.Application;

import androidx.lifecycle.AndroidViewModel;

import com.example.agentcostcontrol.data.SupabaseRepository;

/** Retains the Auth refresh lock when Android recreates the activity. */
public final class AccountDataViewModel extends AndroidViewModel {
    private final SupabaseRepository repository;

    public AccountDataViewModel(Application application) {
        super(application);
        repository = new SupabaseRepository(application);
    }

    public SupabaseRepository getRepository() {
        return repository;
    }
}
