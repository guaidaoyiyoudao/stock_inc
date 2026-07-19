package com.stock.dividend.di

import com.stock.dividend.data.scan.MlKitTextRecognitionService
import com.stock.dividend.data.scan.TextRecognitionService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {

    @Binds
    @Singleton
    abstract fun bindTextRecognitionService(
        impl: MlKitTextRecognitionService
    ): TextRecognitionService
}
