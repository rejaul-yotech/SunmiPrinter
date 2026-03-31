package com.yotech.valtprinter.di

import com.yotech.valtprinter.data.PrinterRepositoryImpl
import com.yotech.valtprinter.domain.repository.PrinterRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PrinterModule {

    @Binds
    @Singleton
    abstract fun bindPrinterRepository(
        impl: PrinterRepositoryImpl
    ): PrinterRepository
}