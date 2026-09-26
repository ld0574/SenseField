"""YOLOX-Nano experiment for cropped minimap enemy portraits."""

from __future__ import annotations

import os

import torch.nn as nn

from yolox.exp import Exp as BaseExp


class Exp(BaseExp):
    def __init__(self) -> None:
        super().__init__()
        self.num_classes = 1
        self.depth = 0.33
        self.width = 0.25
        self.depthwise = True
        self.input_size = (256, 256)
        self.test_size = (256, 256)
        self.multiscale_range = 0
        self.random_size = (8, 8)
        self.data_dir = os.environ.get("MAPASSIST_COCO_DIR")
        self.train_ann = "instances_train2017.json"
        self.val_ann = "instances_val2017.json"
        self.test_ann = "instances_test2017.json"
        self.data_num_workers = 0
        self.mosaic_prob = 0.5
        self.mosaic_scale = (0.7, 1.3)
        self.enable_mixup = False
        self.mixup_prob = 0.0
        self.hsv_prob = 0.8
        self.flip_prob = 0.5
        self.degrees = 5.0
        self.translate = 0.08
        self.shear = 1.0
        self.warmup_epochs = 5
        self.max_epoch = 120
        self.no_aug_epochs = 20
        self.eval_interval = 5
        self.print_interval = 5
        self.save_history_ckpt = False
        self.test_conf = 0.001
        self.nmsthre = 0.5
        self.seed = 20260926
        self.exp_name = "yolox_nano_minimap"

    def get_model(self, sublinear: bool = False):
        del sublinear

        def init_yolo(module: nn.Module) -> None:
            for layer in module.modules():
                if isinstance(layer, nn.BatchNorm2d):
                    layer.eps = 1e-3
                    layer.momentum = 0.03

        if getattr(self, "model", None) is None:
            from yolox.models import YOLOPAFPN, YOLOX, YOLOXHead

            in_channels = [256, 512, 1024]
            backbone = YOLOPAFPN(
                self.depth,
                self.width,
                in_channels=in_channels,
                act=self.act,
                depthwise=self.depthwise,
            )
            head = YOLOXHead(
                self.num_classes,
                self.width,
                in_channels=in_channels,
                act=self.act,
                depthwise=self.depthwise,
            )
            self.model = YOLOX(backbone, head)
        self.model.apply(init_yolo)
        self.model.head.initialize_biases(1e-2)
        return self.model
